/*
 * Copyright 2005-2010 LAMP/EPFL
 */
// $Id$

package scala.tools.eclipse.javaelements

import java.lang.reflect.InvocationTargetException
import java.util.{ HashMap => JHashMap, Map => JMap }
import org.eclipse.core.resources.{ IFile, IResource }
import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.core.runtime.Platform
import org.eclipse.jdt.core.{ IBuffer, ICompilationUnit, IJavaElement, IType, WorkingCopyOwner }
import org.eclipse.jdt.core.compiler.IProblem
import org.eclipse.jdt.internal.core.util.HandleFactory
import org.eclipse.jdt.internal.core.{ BufferManager, CompilationUnit => JDTCompilationUnit, OpenableElementInfo, PackageFragment }
import org.eclipse.jdt.internal.corext.util.JavaModelUtil
import org.eclipse.swt.widgets.Display
import scala.tools.nsc.io.{ AbstractFile, VirtualFile }
import scala.tools.eclipse.contribution.weaving.jdt.IScalaSourceFile
import scala.tools.eclipse.util.EclipseFile
import org.eclipse.jdt.core.compiler.CharOperation
import scala.tools.nsc.interactive.Response
import scala.tools.eclipse.reconciliation.ReconciliationParticipantsExtensionPoint
import org.eclipse.jdt.core.JavaModelException

object ScalaSourceFile {
  val handleFactory = new HandleFactory
  
  def createFromPath(path : String) : Option[ScalaSourceFile] = {
    if (!path.endsWith(".scala"))
      None
    else
      handleFactory.createOpenable(path, null) match {
        case ssf : ScalaSourceFile => Some(ssf)
        case _ => None
      }
  }
}

class ScalaSourceFile(fragment : PackageFragment, elementName: String, workingCopyOwner : WorkingCopyOwner) 
  extends JDTCompilationUnit(fragment, elementName, workingCopyOwner) with ScalaCompilationUnit with IScalaSourceFile {

  private lazy val isLessThanJuno = {
    val JDTVersion = Platform.getBundle("org.eclipse.jdt.core").getVersion
    JDTVersion.getMajor == 3 && JDTVersion.getMinor < 8
  } 

  private def versionAwareOpenWhenClosed(info: OpenableElementInfo, monitor: IProgressMonitor): Unit = {
    
    if (isLessThanJuno) {
      try {
        val clazz = classOf[JDTCompilationUnit]
        val method = clazz.getMethod("openWhenClosed", classOf[OpenableElementInfo], classOf[IProgressMonitor])
        method.invoke(this, info, monitor)
      } catch {
        case e: IllegalArgumentException =>
          throw new RuntimeException(e)
        case e: IllegalAccessException =>
          throw new RuntimeException(e);
        case e: InvocationTargetException =>
          throw new RuntimeException(e);
      }
    } else {
      openWhenClosed(info, true, monitor)
    }
  }

  override def getMainTypeName : Array[Char] =
    getElementName.substring(0, getElementName.length - ".scala".length).toCharArray()

  /** Schedule this source file for reconciliation. Add the file to 
   *  the loaded files managed by the presentation compiler.
   */
  override def scheduleReconcile(): Response[Unit] = {
    // askReload first
    val res = project.withSourceFile(this) { (sf, compiler) =>
      compiler.askReload(this, getContents)
    } ()
    
    this.reconcile(
        ICompilationUnit.NO_AST,
        false /* don't force problem detection */,
        null /* use primary owner */,
        null /* no progress monitor */);
    
    res
  }

  override def reconcile(
      astLevel : Int,
      reconcileFlags : Int,
      workingCopyOwner : WorkingCopyOwner,
      monitor : IProgressMonitor) : org.eclipse.jdt.core.dom.CompilationUnit = {    
    ReconciliationParticipantsExtensionPoint.runBefore(this, monitor, workingCopyOwner)
    val result = super.reconcile(ICompilationUnit.NO_AST, reconcileFlags, workingCopyOwner, monitor)
    ReconciliationParticipantsExtensionPoint.runAfter(this, monitor, workingCopyOwner)
    result
  }

  override def makeConsistent(
    astLevel : Int,
    resolveBindings : Boolean,
    reconcileFlags : Int,
    problems : JHashMap[_,_],
    monitor : IProgressMonitor) : org.eclipse.jdt.core.dom.CompilationUnit = {
    val info = createElementInfo.asInstanceOf[OpenableElementInfo]
    versionAwareOpenWhenClosed(info, monitor)
    null
  }

  override def codeSelect(offset : Int, length : Int, workingCopyOwner : WorkingCopyOwner) : Array[IJavaElement] =
    codeSelect(this, offset, length, workingCopyOwner)

  override def getProblemRequestor = getPerWorkingCopyInfo

  override lazy val file : AbstractFile = { 
    val res = try { getCorrespondingResource } catch { case _: JavaModelException => null }
    res match {
      case f : IFile => new EclipseFile(f)
      case _ => new VirtualFile(getElementName, getPath.toString)
    }
  }

  def getProblems : Array[IProblem] = withSourceFile { (src, compiler) =>
    val problems = compiler.problemsOf(this)
    if (problems.isEmpty) null else problems.toArray
  } (null)
  
  override def getType(name : String) : IType = new LazyToplevelClass(this, name)
  
  override def getContents() : Array[Char] = {
    // in the following case, super#getContents() logs an exception for no good reason
    if (getBufferManager().getBuffer(this) == null && getResource().getLocation() == null && getResource().getLocationURI() == null) {
      return CharOperation.NO_CHAR
    }
    return super.getContents()
  }

  /** Makes sure {{{this}}} source is not in the ignore buffer of the compiler and ask the compiler to reload it. */
  final def forceReload(): Unit = project.doWithPresentationCompiler { compiler =>
    compiler.askToDoFirst(this)
    reload()
  }
  
  /** Ask the compiler to reload {{{this}}} source. */
  final def reload(): Unit = project.doWithPresentationCompiler { _.askReload(this, getContents) }

  /** Ask the compiler to discard {{{this}}} source. */
  final def discard(): Unit = project.doWithPresentationCompiler { compiler =>
    compiler.discardSourceFile(this)
  }
}
