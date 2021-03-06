package org.netbeans.modules.scala.sbt.console

import java.awt.Color
import java.awt.Font
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.PipedInputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.util.logging.Logger
import java.util.prefs.Preferences
import java.util.regex.Pattern
import javax.swing._
import javax.swing.text.AttributeSet
import javax.swing.text.DefaultCaret
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import org.netbeans.api.extexecution.ExecutionDescriptor
import org.netbeans.api.extexecution.ExecutionService
import org.netbeans.api.extexecution.ExternalProcessBuilder
import org.netbeans.api.progress.ProgressHandleFactory
import org.netbeans.api.project.Project
import org.netbeans.modules.scala.core.ScalaExecution
import org.netbeans.modules.scala.console.AnsiConsoleOutputStream
import org.netbeans.modules.scala.console.ConsoleInputOutput
import org.netbeans.modules.scala.console.ConsoleTerminal
import org.netbeans.modules.scala.console.ConsoleOutputLineParser
import org.netbeans.modules.scala.console.TerminalInput
import org.netbeans.modules.scala.console.TopComponentId
import org.openide.filesystems.FileUtil
import org.openide.util.Cancellable
import org.openide.util.ImageUtilities
import org.openide.util.NbBundle
import org.openide.windows.TopComponent
import org.openide.windows.WindowManager
import scala.collection.mutable

/**
 *
 * @author Caoyuan Deng
 */
class SBTConsoleTopComponent private (project: Project, val isDebug: Boolean) extends TopComponent {
  import SBTConsoleTopComponent._
  import SBTConsoleSettings._

  /**
   * @Note this id will be escaped by PersistenceManager and for findTopCompoment(id)
   */
  override protected val preferredID = toPreferredId(project, sn)

  private val log = Logger.getLogger(getClass.getName)

  private val mimeType = "text/x-sbt"
  private var console: SbtConsoleTerminal = _

  initComponents

  private def initComponents() {
    setLayout(new java.awt.BorderLayout())
    setName("SBT " + project.getProjectDirectory.getName + (if (isDebug) " [debug]" else ""))
    setToolTipText(NbBundle.getMessage(classOf[SBTConsoleTopComponent], "HINT_SBTConsoleTopComponent") + " for " + project.getProjectDirectory.getPath)
    setIcon(ImageUtilities.loadImage(ICON_PATH, true))
  }

  /**
   * @Note
   * Since NetBeans 7.2, if persistenceType is set to PERSISTENCE_NEVER,
   * WindowManager.getDefault.findTopComponent(tcId) will always return null
   */
  override def getPersistenceType = TopComponent.PERSISTENCE_NEVER

  override def canClose = true // make sure this tc can be truely closed

  override def open() {
    /**
     * @Note
     * mode.dockInto(this) seems will close this first if this.isOpened()
     * So, when call open(), try to check if it was already opened, if true,
     * no need to call open() again
     */
    val mode = WindowManager.getDefault.findMode("output")
    if (mode != null) {
      mode.dockInto(this)
    }
    super.open
  }

  override protected def componentOpened() {
    // always create a new terminal when is opened/reopend
    console = createTerminal
    super.componentOpened
  }

  override protected def componentClosed() {
    if (console != null) {
      try {
        console.close
      } catch {
        case ex: Exception => log.warning(ex.getMessage)
      }
      console == null
    }
    super.componentClosed
  }

  override protected def componentActivated() {
    super.componentActivated
  }

  override protected def componentDeactivated() {
    super.componentDeactivated
  }

  override def requestFocus() {
    if (console != null) {
      console.area.requestFocus
    }
  }

  override def requestFocusInWindow: Boolean = {
    if (console != null) {
      console.area.requestFocusInWindow
    } else {
      false
    }
  }

  private def getDefaultFont = {
    var size = UIManager.getInt("uiFontSize")
    if (size < 3) {
      size = UIManager.getInt("customFontSize")
      val f = UIManager.get("controlFont").asInstanceOf[Font]
      if (f != null) {
        size = f.getSize
      }
    }
    if (size < 3) {
      size = 11
    }
    new Font("Monospaced", Font.PLAIN, size)
  }

  private val packagePreferences: Preferences = Preferences.userNodeForPackage(this.getClass)
  private val maxHeapSize = packagePreferences.getInt(SbtMaxHeapSizeKey, DefaultMaxHeapSizeMB.toInt)
  private val initialHeapSize = packagePreferences.getInt(SbtInitialHeapSizeKey, DefaultInitialHeapSizeMB.toInt)
  private val maxPermGenSize = packagePreferences.getInt(SbtMaxPermGenSizeKey, DefaultMaxPermGenSizeMB.toInt)
  private val additionalArgs = packagePreferences.get(SbtAdditionalArgsKey, DefaultAdditionalArgs)

  private def getSbtArgs(sbtHome: String): (String, List[String]) = {
    val args = new mutable.ListBuffer[String]()

    val executable = ScalaExecution.getJavaHome + File.separator + "bin" + File.separator + "java" // NOI18N
    // XXX Do I need java.exe on Windows?
    args += s"-Xmx${maxHeapSize}m"
    args += s"-Xms${initialHeapSize}m"
    args += s"-Xss1m"
    args ++= additionalArgs.split(" ")
    args += s"-XX:MaxPermSize=${maxPermGenSize}m"

    args += "-Dsbt.log.noformat=true"
    /**
     * @Note:
     * jline's UnitTerminal will hang in my Mac OS, when call "stty(...)", why?
     * Also, from Scala-2.7.1, jline is used for scala shell, we should
     * disable it here by add "-Djline.terminal=jline.UnsupportedTerminal"?
     * And jline may cause terminal unresponsed after netbeans quited.
     */
    //args += "-Djline.terminal=jline.UnsupportedTerminal"
    args += "-Djline.WindowsTerminal.directConsole=false"

    // TODO - turn off verifier?

    // Main class
    args += "-jar"
    args += ScalaExecution.getSbtLaunchJar(sbtHome) map (_.getAbsolutePath) getOrElse "" // NOI18N

    (executable, args.toList)
  }

  private def createTerminal: SbtConsoleTerminal = {
    val textView = new JTextPane()
    textView.setFont(new Font("Monospaced", Font.PLAIN, 13))
    setBorder(BorderFactory.createEmptyBorder)

    // @see core.output2/org.netbeans.core.output2.ui/AbstractOutputPane
    val c = UIManager.getColor("nb.output.selectionBackground")
    if (c != null) {
      textView.setSelectionColor(c)
    }

    textView.getDocument.putProperty("mimeType", mimeType)
    textView.setForeground(Color.BLACK)
    textView.setBackground(Color.WHITE)
    textView.setCaretColor(Color.BLACK)
    textView.getCaret.asInstanceOf[DefaultCaret].setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE)

    val pane = new JScrollPane()
    pane.setViewportView(textView)
    pane.setBorder(BorderFactory.createEmptyBorder)
    pane.setViewportBorder(BorderFactory.createEmptyBorder)
    add(pane)
    validate

    val pwd = FileUtil.toFile(project.getProjectDirectory)
    val sbtHome = ScalaExecution.getSbtHome
    val sbtLaunchJar = ScalaExecution.getSbtLaunchJar(sbtHome)

    val (executable, _args) = getSbtArgs(sbtHome)
    val args = if (isDebug) debugOpts(5005) :: _args else _args
    // XXX under Mac OS jdk7, the java.home is point to /Library/Java/JavaVirtualMachines/jdk1.7.0_xx.jdk/Contents/Home/jre
    // instead of /Library/Java/JavaVirtualMachines/jdk1.7.0_xx.jdk/Contents/Home/, which cause the lack of javac
    //builder = builder.addEnvironmentVariable("JAVA_HOME", SBTExecution.getJavaHome)
    // XXX under Mac OS jdk7, the java.home is point to /Library/Java/JavaVirtualMachines/jdk1.7.0_xx.jdk/Contents/Home/jre
    // instead of /Library/Java/JavaVirtualMachines/jdk1.7.0_xx.jdk/Contents/Home/, which cause the lack of javac
    //builder = builder.addEnvironmentVariable("JAVA_HOME", SBTExecution.getJavaHome)
    val builder = args.foldLeft(new ExternalProcessBuilder(executable))(_ addArgument _).workingDirectory(pwd)
    log.info(args.mkString(
      "\n==== Sbt console args ====\n" + executable + "\n",
      "\n",
      "\n==== End of Sbt console args ===="))

    val pipedIn = new PipedInputStream()
    val console = new SbtConsoleTerminal(
      textView, pipedIn,
      NbBundle.getMessage(classOf[SBTConsoleTopComponent],
        "SBTConsoleWelcome") + "\n" +
        "sbt-launch=" + sbtLaunchJar.getOrElse("none") + "\n")

    if (ScalaExecution.isWindows) {
      console.terminalInput.terminalId = TerminalInput.JLineWindows
    }

    val consoleOut = new AnsiConsoleOutputStream(console)
    val in = new InputStreamReader(pipedIn)
    val out = new PrintWriter(new PrintStream(consoleOut))
    val err = new PrintWriter(new PrintStream(consoleOut))
    val inputOutput = new ConsoleInputOutput(in, out, err)

    val execDescriptor = new ExecutionDescriptor().frontWindow(true).controllable(true).inputVisible(true).inputOutput(inputOutput)
      .postExecution(new Runnable() {
        override def run() {
          textView.setEditable(false)
          SwingUtilities.invokeLater(new Runnable() {
            override def run() {
              SBTConsoleTopComponent.this.close
              SBTConsoleTopComponent.this.removeAll
            }
          })
        }
      })

    val executionService = ExecutionService.newService(builder, execDescriptor, "Sbt Shell")
    console.underlyingTask = Option(executionService.run())

    console
  }

  var isRunningCommand: Boolean = _

}

object SBTConsoleSettings {
  val SbtMaxHeapSizeKey = "SBT_MAX_HEAP_MB"
  val SbtInitialHeapSizeKey = "SBT_INIT_HEAP_MB"
  val SbtMaxPermGenSizeKey = "SBT_MAX_PERMGEN_MB"
  val SbtAdditionalArgsKey = "SBT_ADDTIONAL_ARGS"

  val DefaultMaxHeapSizeMB = "512"
  val DefaultInitialHeapSizeMB = "128"
  val DefaultMaxPermGenSizeMB = "128"
  val DefaultAdditionalArgs = "-XX:+UseCodeCacheFlushing -XX:+CMSClassUnloadingEnabled"
}

object SBTConsoleTopComponent {
  private val log = Logger.getLogger(this.getClass.getName)

  private def apply(project: Project, isDebug: Boolean) = {
    sn += 1
    new SBTConsoleTopComponent(project, isDebug)
  }

  private val projectToDefault = new mutable.WeakHashMap[Project, SBTConsoleTopComponent]()
  def defaultFor(project: Project) = projectToDefault.get(project)

  val defaultFg = Color.BLACK
  val defaultBg = Color.WHITE
  val linkFg = Color.BLUE

  /**
   * path to the icon used by the component and its open action
   */
  val ICON_PATH = "org/netbeans/modules/scala/sbt/resources/sbt.png"

  private val CompName = "SBTConsole"
  private val EmptyAction = { _: String => () }
  private var sn = 0

  /**
   * @see org.netbeans.core.windows.persistence.PersistenceManager
   */
  private def toPreferredId(project: Project, sn: Int) = CompName + project.getProjectDirectory.getPath + "_" + sn
  private def toEscapedPreferredId(project: Project, sn: Int) = TopComponentId.escape(toPreferredId(project, sn))

  def debugOpts(port: Int) = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=" + port

  def openNewInstance(project: Project, commands: List[String], isDebug: Boolean)(postAction: String => Unit = EmptyAction) =
    openInstance(project, commands, isForceNew = true, isDebug, false)(postAction)

  def openInstance(project: Project, commands: List[String], isDebug: Boolean)(postAction: String => Unit = EmptyAction): Unit =
    openInstance(project, commands, isForceNew = false, isDebug, false)(postAction)

  /**
   * Obtain the SBTConsoleTopComponent instance by project
   */
  private def openInstance(project: Project, commands: List[String], isForceNew: Boolean, isDebug: Boolean, background: Boolean)(postAction: String => Unit) {
    val (tc, isNewCreated) = if (isDebug) {
      (SBTConsoleTopComponent(project, isDebug), true)
    } else {
      projectToDefault.get(project) match {
        case None =>
          val default = SBTConsoleTopComponent(project, isDebug)
          projectToDefault.put(project, default)
          (default, true)
        case Some(tc) =>
          if (isForceNew || tc.isRunningCommand) {
            (SBTConsoleTopComponent(project, isDebug), true)
          } else {
            (tc, false)
          }
      }
    }

    tc.isRunningCommand = true

    val runnableTask = new Runnable() {
      def run {

        val progressHandle = ProgressHandleFactory.createHandle("Running sbt commnad...",
          new Cancellable() {
            def cancel: Boolean = false // XXX todo possible for a AWT Event dispatch thread?
          })

        progressHandle.start

        if (!tc.isOpened) {
          tc.open
        }

        if (!background) {
          tc.requestActive
        }

        val results = commands map tc.console.runCommand

        if (background && !isNewCreated) {
          tc.close
        }

        postAction(results.lastOption getOrElse null)

        tc.isRunningCommand = false

        progressHandle.finish
      }
    }

    SwingUtilities.invokeLater(runnableTask)
  }

  class SbtConsoleTerminal(_area: JTextPane, pipedIn: PipedInputStream, welcome: String) extends ConsoleTerminal(_area, pipedIn, welcome) {

    @throws(classOf[IOException])
    override protected def handleClose() {
      runCommand("exit") // try to exit underlying process gracefully
      super.handleClose()
    }

    override protected val lineParser = new ConsoleOutputLineParser() {

      val INFO_PREFIX = "[info]"
      val WARN_PREFIX = "[warn]"
      val ERROR_PREFIX = "[error]"
      val SUCCESS_PREFIX = "[success]"

      val WINDOWS_DRIVE = "(?:[a-zA-Z]\\:)?"
      val FILE_CHAR = "[^\\[\\]\\:\\\"]" // not []:", \s is allowd
      val FILE = "(" + WINDOWS_DRIVE + "(?:" + FILE_CHAR + "*))"
      val LINE = "(([1-9][0-9]*))" // line number
      val ROL = ".*\\s?\\s?" // rest of line (may end with "\n" or "\r\n")
      val SEP = "\\:" // seperator between file path and line number
      val STD_SUFFIX = FILE + SEP + LINE + ROL // ((?:[a-zA-Z]\:)?(?:[^\[\]\:\"]*))\:(([1-9][0-9]*)).*\s?

      val rERROR_WITH_FILE = Pattern.compile("\\Q" + ERROR_PREFIX + "\\E" + "\\s?" + STD_SUFFIX) // \Q[error]\E\s?((?:[a-zA-Z]\:)?(?:[^\[\]\:\"]*))\:(([1-9][0-9]*)).*\s?
      val rWARN_WITH_FILE = Pattern.compile("\\Q" + WARN_PREFIX + "\\E" + "\\s?" + STD_SUFFIX) //  \Q[warn]\E\s?((?:[a-zA-Z]\:)?(?:[^\[\]\:\"]*))\:(([1-9][0-9]*)).*\s?

      lazy val infoStyle = {
        val x = new SimpleAttributeSet()
        StyleConstants.setForeground(x, defaultStyle.getAttribute(StyleConstants.Foreground).asInstanceOf[Color])
        StyleConstants.setBackground(x, defaultStyle.getAttribute(StyleConstants.Background).asInstanceOf[Color])
        x
      }
      lazy val warnStyle = {
        val x = new SimpleAttributeSet()
        StyleConstants.setForeground(x, new Color(0xB9, 0x7C, 0x00))
        StyleConstants.setBackground(x, defaultStyle.getAttribute(StyleConstants.Background).asInstanceOf[Color])
        x
      }
      lazy val errorStyle = {
        val x = new SimpleAttributeSet()
        StyleConstants.setForeground(x, Color.RED)
        StyleConstants.setBackground(x, defaultStyle.getAttribute(StyleConstants.Background).asInstanceOf[Color])
        x
      }
      lazy val successStyle = {
        val x = new SimpleAttributeSet()
        StyleConstants.setForeground(x, Color.GREEN)
        StyleConstants.setBackground(x, defaultStyle.getAttribute(StyleConstants.Background).asInstanceOf[Color])
        x
      }

      def parseLine(line: String): Array[(String, AttributeSet)] = {
        if (line.length < 6) {
          Array((line, defaultStyle))
        } else {
          val texts = new mutable.ArrayBuffer[(String, AttributeSet)]()
          val testRest_style = if (line.startsWith(ERROR_PREFIX)) {

            val m = rERROR_WITH_FILE.matcher(line)
            if (m.matches && m.groupCount >= 3) {
              texts += (("[", defaultStyle))
              texts += (("error", errorStyle))
              texts += (("] ", defaultStyle))
              val textRest = line.substring(ERROR_PREFIX.length + 1, line.length)

              val fileName = m.group(1)
              val lineNo = m.group(2)
              val linkStyle = new SimpleAttributeSet()
              StyleConstants.setForeground(linkStyle, linkFg)
              StyleConstants.setUnderline(linkStyle, true)
              linkStyle.addAttribute("file", fileName)
              linkStyle.addAttribute("line", lineNo)

              (textRest, linkStyle)
            } else {
              texts += (("[", defaultStyle))
              texts += (("error", errorStyle))
              texts += (("]", defaultStyle))
              val textRest = line.substring(ERROR_PREFIX.length, line.length)

              (textRest, errorStyle)
            }

          } else if (line.startsWith(WARN_PREFIX)) {

            val m = rWARN_WITH_FILE.matcher(line)
            if (m.matches && m.groupCount >= 3) {
              texts += (("[", defaultStyle))
              texts += (("warn", warnStyle))
              texts += (("] ", defaultStyle))
              val textRest = line.substring(WARN_PREFIX.length + 1, line.length)

              val fileName = m.group(1)
              val lineNo = m.group(2)
              val linkStyle = new SimpleAttributeSet()
              StyleConstants.setForeground(linkStyle, linkFg)
              StyleConstants.setUnderline(linkStyle, true)
              linkStyle.addAttribute("file", fileName)
              linkStyle.addAttribute("line", lineNo)

              (textRest, linkStyle)
            } else {
              texts += (("[", defaultStyle))
              texts += (("warn", warnStyle))
              texts += (("]", defaultStyle))
              val textRest = line.substring(WARN_PREFIX.length, line.length)

              (textRest, warnStyle)
            }

          } else if (line.startsWith(INFO_PREFIX)) {

            texts += (("[", defaultStyle))
            texts += (("info", infoStyle))
            texts += (("]", defaultStyle))
            val textRest = line.substring(INFO_PREFIX.length, line.length)

            (textRest, defaultStyle)

          } else if (line.startsWith(SUCCESS_PREFIX)) {

            texts += (("[", defaultStyle))
            texts += (("success", successStyle))
            texts += (("]", defaultStyle))
            val textRest = line.substring(SUCCESS_PREFIX.length, line.length)

            (textRest, defaultStyle)

          } else {
            (line, defaultStyle)
          }

          texts += testRest_style

          texts.toArray
        }
      }
    }
  }

}
