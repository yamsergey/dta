package io.yamsergey.dta.studio.schemas

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType
import com.intellij.psi.PsiWildcardType
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.InheritanceUtil

/** Which body a derived mapping decodes. */
enum class BodySide(val wire: String) { REQUEST("request"), RESPONSE("response") }

/** A mapping derived from code: what `network.schema.sync` takes in `mappings`. */
data class DerivedMapping(val urlPattern: String, val part: BodySide, val type: String, val method: String, val location: String?)

/** What derivation found: the mappings, and what it could not map, each with the reason. */
data class Derivation(val mappings: List<DerivedMapping>, val unmapped: List<String>)

/**
 * Mappings from Retrofit declarations (feature 013, research R4): for each method annotated with
 * a `retrofit2.http` verb, the `@Body` parameter's type is the request body and the return type
 * (unwrapped from `Call`, `Response`, `Single`… or a suspend function's continuation) the response
 * body — when it is a protobuf or Wire message, matched by name to the synced [types]. Nothing is
 * guessed: an endpoint whose type matches no type, or several, is listed as unmapped.
 */
object RetrofitMappings {
    private val VERBS = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "HTTP")
    private val MESSAGE_BASES = listOf(
        "com.google.protobuf.GeneratedMessage", "com.google.protobuf.GeneratedMessageV3",
        "com.google.protobuf.GeneratedMessageLite", "com.squareup.wire.Message",
    )
    private val WRAPPERS = setOf(
        "retrofit2.Call", "retrofit2.Response", "kotlinx.coroutines.Deferred", "io.reactivex.Single", "io.reactivex.Observable",
        "io.reactivex.rxjava3.core.Single", "io.reactivex.rxjava3.core.Observable", "kotlinx.coroutines.flow.Flow",
    )

    /** In a non-blocking read action: the search yields to the IDE's writes and waits for indexing. */
    suspend fun derive(project: Project, types: Set<String>): Derivation =
        com.intellij.openapi.application.smartReadAction(project) { find(project, types) }

    /** The search itself; the caller holds read access. */
    fun find(project: Project, types: Set<String>): Derivation {
        val all = GlobalSearchScope.allScope(project)
        val scope = GlobalSearchScope.projectScope(project)
        val facade = JavaPsiFacade.getInstance(project)
        val mappings = mutableListOf<DerivedMapping>()
        val unmapped = mutableListOf<String>()
        for (verb in VERBS) {
            val annotation = facade.findClass("retrofit2.http.$verb", all) ?: continue
            AnnotatedElementsSearch.searchPsiMethods(annotation, scope).findAll().forEach { method ->
                val a = method.getAnnotation("retrofit2.http.$verb") ?: return@forEach
                val httpMethod = if (verb == "HTTP") string(a, "method")?.uppercase() ?: return@forEach else verb
                val path = string(a, if (verb == "HTTP") "path" else "value").orEmpty()
                val pattern = "*/" + path.trim().trimStart('/').replace(Regex("""\{[^}]*}"""), "*")
                val location = location(method)
                method.parameterList.parameters.firstOrNull { it.getAnnotation("retrofit2.http.Body") != null }?.let { body ->
                    resolve(body.type, types, "${method.name} request body", unmapped)?.let { mappings += DerivedMapping(pattern, BodySide.REQUEST, it, httpMethod, location) }
                }
                responseType(method)?.let { response ->
                    resolve(response, types, "${method.name} response", unmapped)?.let { mappings += DerivedMapping(pattern, BodySide.RESPONSE, it, httpMethod, location) }
                }
            }
        }
        return Derivation(mappings, unmapped)
    }

    /** The full message name of a protobuf/Wire type; null (and listed) when it is not one or not unique. */
    private fun resolve(type: PsiType, types: Set<String>, what: String, unmapped: MutableList<String>): String? {
        val cls = (type as? PsiClassType)?.resolve() ?: return null
        if (MESSAGE_BASES.none { InheritanceUtil.isInheritor(cls, it) }) return null
        val name = cls.name ?: return null
        val candidates = types.filter { it.substringAfterLast('.') == name }
        return when (candidates.size) {
            1 -> candidates.single()
            0 -> null.also { unmapped += "$what: ${cls.qualifiedName} is not a message type of the synced schemas" }
            else -> null.also { unmapped += "$what: $name matches several message types (${candidates.sorted().joinToString(", ")}); map it by hand" }
        }
    }

    /** The response type: the return type unwrapped, or a suspend function's continuation type. */
    private fun responseType(method: PsiMethod): PsiType? {
        val continuation = method.parameterList.parameters.lastOrNull()?.type as? PsiClassType
        val raw = if (continuation?.resolve()?.qualifiedName == "kotlin.coroutines.Continuation") {
            continuation.parameters.firstOrNull()?.let { (it as? PsiWildcardType)?.bound ?: it }
        } else {
            method.returnType
        }
        return unwrap(raw)
    }

    private fun unwrap(type: PsiType?): PsiType? {
        var t = type as? PsiClassType ?: return type
        while (true) {
            val cls: PsiClass = t.resolve() ?: return t
            if (cls.qualifiedName !in WRAPPERS) return t
            t = t.parameters.firstOrNull()?.let { (it as? PsiWildcardType)?.bound ?: it } as? PsiClassType ?: return null
        }
    }

    private fun string(a: PsiAnnotation, name: String): String? =
        (a.findAttributeValue(name) as? PsiLiteralExpression)?.value as? String
            ?: a.findAttributeValue(name)?.let { com.intellij.psi.impl.JavaConstantExpressionEvaluator.computeConstantExpression(it as? com.intellij.psi.PsiExpression, false) as? String }

    private fun location(method: PsiMethod): String? {
        val file = method.containingFile ?: return null
        val doc = PsiDocumentManager.getInstance(method.project).getDocument(file) ?: return file.name
        return "${file.name}:${doc.getLineNumber(method.textOffset) + 1}"
    }
}
