package org.netbeans.modules.scala.sbt.project

import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.io.File
import java.util.Timer
import javax.swing.event.ChangeEvent
import javax.swing.event.ChangeListener
import org.netbeans.api.java.classpath.ClassPath
import org.netbeans.modules.scala.sbt.console.SBTConsoleTopComponent
import org.openide.filesystems.FileUtil
import org.openide.util.NbBundle
import scala.collection.mutable.ArrayBuffer

case class ProjectContext(
  name: String,
  mainJavaSrcs:   Array[(File, File)], 
  testJavaSrcs:   Array[(File, File)], 
  mainScalaSrcs:  Array[(File, File)], 
  testScalaSrcs:  Array[(File, File)], 
  mainCps:        Array[File], 
  testCps:        Array[File],
  depPrjs:        Array[File],
  aggPrjs:        Array[File]
)

/**
 *
 * @author Caoyuan Deng
 */
class SBTResolver(project: SBTProject) extends ChangeListener {
  import SBTResolver._

  private val pcs = new PropertyChangeSupport(this)
  private val projectDir = project.getProjectDirectory
  private var _projectContext: ProjectContext = _
  @volatile private var _isResolvedOrResolving = false

  def isResolvedOrResolving = _isResolvedOrResolving
  def isResolvedOrResolving_=(b: Boolean) {
    val oldvalue = _isResolvedOrResolving
    _isResolvedOrResolving = b
    if (oldvalue != _isResolvedOrResolving) {
      pcs.firePropertyChange(SBT_RESOLVED_STATE_CHANGE, oldvalue, _isResolvedOrResolving)
    }
  }

  def triggerSbtResolution {
    if (!_isResolvedOrResolving) {
      _isResolvedOrResolving = true
      val rootProject = project.getRootProject
      val commands = List("eclipse gen-netbeans=true skip-parents=false")
      val showMessage = NbBundle.getMessage(classOf[SBTResolver], "LBL_Resolving_Progress")
      SBTConsoleTopComponent.openInstance(rootProject, false, commands, showMessage){result =>
        pcs.firePropertyChange(SBT_RESOLVED, null, null)
      }
    }
  }

  def projectContext = synchronized {
    if (_projectContext == null) {
      dirWatcher.addChangeListener(projectDir, this)
      projectDir.getFileObject(DESCRIPTOR_FILE_NAME) match {
        case null => 
          // set Empty one as soon as possible, so it can be cover by the one get via triggerSbtResolution laster
          _projectContext = EmptyContext 
          triggerSbtResolution
        case file =>
          _projectContext = parseClasspathXml(FileUtil.toFile(file))
      }
    }
    
    _projectContext
  }
  
  def stateChanged(evt: ChangeEvent) {
    evt match {
      case FileAdded(file, time) if file.getParent == projectDir =>
        println("Got " + evt + ", " + file.getPath)
        val oldContext = _projectContext
        _projectContext = parseClasspathXml(FileUtil.toFile(file))
        pcs.firePropertyChange(DESCRIPTOR_CHANGE, oldContext, _projectContext)
        
      case FileModified(file, time) if file.getParent == projectDir =>
        println("Got " + evt + ", " + file.getPath)
        val oldContext = _projectContext
        _projectContext = parseClasspathXml(FileUtil.toFile(file))
        pcs.firePropertyChange(DESCRIPTOR_CHANGE, oldContext, _projectContext)
      
      case _ =>
    }
  }

  private def parseClasspathXml(file: File): ProjectContext = {
    var name: String = null
    val mainJavaSrcs  = new ArrayBuffer[(File, File)]()
    val testJavaSrcs  = new ArrayBuffer[(File, File)]()
    val mainScalaSrcs = new ArrayBuffer[(File, File)]()
    val testScalaSrcs = new ArrayBuffer[(File, File)]()
    val mainCps = new ArrayBuffer[File]()
    val testCps = new ArrayBuffer[File]()
    val depPrjs = new ArrayBuffer[File]()
    val aggPrjs = new ArrayBuffer[File]()

    val projectFo = project.getProjectDirectory
    val projectDir = FileUtil.toFile(projectFo)
    try {
      val classpath = scala.xml.XML.loadFile(file)
      classpath match {
        case context @ <classpath>{ entries @ _* }</classpath> =>
          name = (context \ "@name").text.trim
          for (entry @ <classpathentry>{ _* }</classpathentry> <- entries) {
            (entry \ "@kind").text match {
              case "src" =>
                val path = (entry \ "@path").text.trim
                val isTest = (entry \ "@scope").text.trim.equalsIgnoreCase("test")
                val isDepProject = !((entry \ "@exported") isEmpty)
                
                val srcFo = projectFo.getFileObject(path)

                val output = (entry \ "@output").text.trim // classes folder
                val outDir = if (isDepProject) {
                  new File(output)
                } else {
                  new File(projectDir, output)
                }

                if (srcFo != null && !isDepProject) {
                  val isJava = srcFo.getPath.split("/") find (_ == "java") isDefined
                  val srcDir = FileUtil.toFile(srcFo)
                  val srcs = if (isTest) {
                    if (isJava) {
                      testJavaSrcs
                    } else {
                      testScalaSrcs
                    }
                  } else {
                    if (isJava) {
                      mainJavaSrcs
                    } else {
                      mainScalaSrcs
                    }
                  }
                  srcs += srcDir -> outDir
                }
              
                if (isTest) {
                  testCps += outDir
                } else {
                  mainCps += outDir
                }
              
                if (isDepProject) {
                  val base = (entry \ "@base").text.trim
                  val baseDir = new File(base)
                  if (baseDir.exists) {
                    depPrjs += baseDir
                  }
                }
              
              case "lib" =>
                val path = (entry \ "@path").text.trim
                val isTest = (entry \ "@scope").text.trim.equalsIgnoreCase("test")
                val libFile = new File(path)
                if (libFile.exists) {
                  if (isTest) {
                    testCps += libFile
                  } else {
                    mainCps += libFile
                  }
                }
                
              case "agg" =>
                val base = (entry \ "@base").text.trim
                val baseFile = new File(base)
                if (baseFile.exists) {
                  aggPrjs += baseFile
                }
                
              case _ =>
            }
          }
      }
    } catch {
      case ex: Exception => 
    }
    
    ProjectContext(name,
                   mainJavaSrcs  map {case (s, o) => FileUtil.normalizeFile(s) -> FileUtil.normalizeFile(o)} toArray,
                   testJavaSrcs  map {case (s, o) => FileUtil.normalizeFile(s) -> FileUtil.normalizeFile(o)} toArray,
                   mainScalaSrcs map {case (s, o) => FileUtil.normalizeFile(s) -> FileUtil.normalizeFile(o)} toArray,
                   testScalaSrcs map {case (s, o) => FileUtil.normalizeFile(s) -> FileUtil.normalizeFile(o)} toArray,
                   mainCps map FileUtil.normalizeFile toArray,
                   testCps map FileUtil.normalizeFile toArray,
                   depPrjs map FileUtil.normalizeFile toArray,
                   aggPrjs map FileUtil.normalizeFile toArray)
  }

  def addPropertyChangeListener(propertyChangeListener: PropertyChangeListener) {
    pcs.addPropertyChangeListener(propertyChangeListener)
  }

  def removePropertyChangeListener(propertyChangeListener: PropertyChangeListener) {
    pcs.removePropertyChangeListener(propertyChangeListener)
  }

  def getName: String = {
    if (projectContext != null) {
      projectContext.name 
    } else {
      null
    }
  }
  
  def getResolvedLibraries(scope: String): Array[File] = {
    if (projectContext != null) {
      scope match {
        case ClassPath.COMPILE => projectContext.mainCps //++ libraryEntry.testCps
        case ClassPath.EXECUTE => projectContext.mainCps //++ libraryEntry.testCps
        case ClassPath.SOURCE => projectContext.mainJavaSrcs ++ projectContext.testJavaSrcs ++ projectContext.mainScalaSrcs ++ projectContext.mainScalaSrcs map (_._1)
        case ClassPath.BOOT => projectContext.mainCps filter {cp =>
            val name = cp.getName
            name.endsWith(".jar") && (name.startsWith("scala-library")  ||
                                      name.startsWith("scala-compiler") ||  // necessary?
                                      name.startsWith("scala-reflect")      // necessary?
            )
          }
        case _ => Array()
      }
    } else {
      Array()
    }
  }

  def getSources(tpe: String, test: Boolean): Array[(File, File)] = {
    if (projectContext != null) {
      tpe match {
        case ProjectConstants.SOURCES_TYPE_JAVA =>
          if (test) projectContext.testJavaSrcs else projectContext.mainJavaSrcs
        case ProjectConstants.SOURCES_TYPE_SCALA =>
          if (test) projectContext.testScalaSrcs else projectContext.mainScalaSrcs
        case _ => Array()
      }
    } else {
      Array()
    }
  }
  
  def getDependenciesProjects: Array[File] = {
    if (projectContext != null) {
      projectContext.depPrjs
    } else {
      Array()
    }
  }

  def getAggregateProjects: Array[File] = {
    if (projectContext != null) {
      projectContext.aggPrjs
    } else {
      Array()
    }
  }

  private def equal(o1: Object, o2: Object): Boolean = {
    if (o1 == null) o2 == null else o1.equals(o2)
  }
}

object SBTResolver {
  val DESCRIPTOR_FILE_NAME = ".classpath_nb"
  
  val DESCRIPTOR_CHANGE = "sbtDescriptorChange"
  val SBT_RESOLVED_STATE_CHANGE = "sbtResolvedStateChange"
  val SBT_RESOLVED = "sbtResolved" 
  
  val EmptyContext = ProjectContext(null, 
                                    Array[(File, File)](), 
                                    Array[(File, File)](), 
                                    Array[(File, File)](), 
                                    Array[(File, File)](), 
                                    Array[File](), 
                                    Array[File](),
                                    Array[File](),
                                    Array[File]()
  )
  
  val dirWatcher = new DirWatcher(DESCRIPTOR_FILE_NAME)
  
  private val timer = new Timer
  timer.schedule(dirWatcher, 0, 2000)
}