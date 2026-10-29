package io.yamsergey.dta.studio.source

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.search.GlobalSearchScope
import io.yamsergey.dta.protocol.network.FrameClassifier

/**
 * Opens an initiator frame in the editor, found by its class as the IDE's own stack navigation
 * does: project classes at the frame's line, library classes in their attached sources (or the
 * decompiled class, at the method). Nested, anonymous and lambda classes open in the top-level
 * class's file. Compiler-generated glue (D8 lambdas) has no source and is not found.
 */
class FrameNavigator(private val project: Project) {

    data class Location(val file: VirtualFile, val line: Int?, val offset: Int?)

    fun resolve(className: String, method: String, file: String?, line: Int?): Location? = ReadAction.compute<Location?, RuntimeException> {
        if (FrameClassifier.generated(className, method)) return@compute null
        val cls = findClass(className) ?: return@compute null
        val source = cls.navigationElement
        val vf = source.containingFile?.virtualFile ?: return@compute null
        if (line != null && source !is PsiCompiledElement) {
            Location(vf, line, null)
        } else {
            val target = cls.findMethodsByName(method, false).firstOrNull()?.navigationElement ?: source
            Location(vf, null, target.textOffset)
        }
    }

    /** Opens the frame; false when it is in neither the project nor its libraries. */
    fun open(className: String, method: String, file: String?, line: Int?): Boolean {
        val at = resolve(className, method, file, line) ?: return false
        val descriptor = if (at.line != null) OpenFileDescriptor(project, at.file, (at.line - 1).coerceAtLeast(0), 0)
        else OpenFileDescriptor(project, at.file, at.offset ?: 0)
        descriptor.navigate(true)
        return true
    }

    /** `a.B$C` is `a.B.C`; `a.B$1` and `a.B$run$1` (anonymous, lambdas) fall back to the enclosing class. */
    private fun findClass(binaryName: String): PsiClass? {
        val facade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.allScope(project)
        var name = binaryName
        while (true) {
            facade.findClass(name.replace('$', '.'), scope)?.let { return it }
            val cut = name.lastIndexOf('$')
            if (cut <= 0) return null
            name = name.substring(0, cut)
        }
    }
}
