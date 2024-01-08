@file:Suppress("UnstableApiUsage")

package me.rerere.unocssintellij.documentation

import com.intellij.documentation.mdn.MdnDocumentation
import com.intellij.documentation.mdn.MdnSymbolDocumentation
import com.intellij.lang.css.CSSLanguage
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.lang.documentation.DocumentationSettings
import com.intellij.model.Pointer
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.richcopy.HtmlSyntaxInfoUtil
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.XmlElementFactory
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.css.CssDeclaration
import com.intellij.psi.css.CssElementFactory
import com.intellij.psi.css.impl.util.CssDocumentationProvider
import com.intellij.psi.css.impl.util.MdnDocumentationUtil
import com.intellij.refactoring.suggested.createSmartPointer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.unocssintellij.UnocssConfigManager
import me.rerere.unocssintellij.rpc.ResolveCSSResult
import me.rerere.unocssintellij.settings.UnocssSettingsState
import me.rerere.unocssintellij.util.*

private val remRE = Regex("-?[\\d.]+rem;")

class UnocssDocumentTarget(
    private val targetElement: PsiElement?,
    private val result: ResolveCSSResult,
) : DocumentationTarget {

    override fun computePresentation(): TargetPresentation {
        return TargetPresentation
            .builder("Unocss Document")
            .presentation()
    }

    override fun createPointer(): Pointer<out DocumentationTarget> {
        val pointer = targetElement?.createSmartPointer()
        return Pointer {
            UnocssDocumentTarget(pointer?.dereference(), result)
        }
    }

    override fun computeDocumentation(): DocumentationResult? {
        val project = targetElement?.project ?: return null
        val cssFile: PsiFile = PsiFileFactory.getInstance(project)
            .createFileFromText(CSSLanguage.INSTANCE, resolveRemToPx(result.css))
        return DocumentationResult.asyncDocumentation {
            // Format the css
            WriteCommandAction.runWriteCommandAction(cssFile.project) {
                val doc = PsiDocumentManager.getInstance(cssFile.project)
                    .getDocument(cssFile) ?: return@runWriteCommandAction
                PsiDocumentManager.getInstance(cssFile.project).doPostponedOperationsAndUnblockDocument(doc)
                CodeStyleManager.getInstance(cssFile.project)
                    .reformatText(cssFile, 0, cssFile.textLength)
            }

            DocumentationResult.documentation(buildString {
                append(DocumentationMarkup.DEFINITION_START)
                append("<code>")
                readAction {
                    HtmlSyntaxInfoUtil.appendHighlightedByLexerAndEncodedAsHtmlCodeSnippet(
                        this,
                        cssFile.project,
                        CSSLanguage.INSTANCE,
                        cssFile.text,
                        DocumentationSettings.getHighlightingSaturation(true)
                    )
                }
                append("</code>")
                append(DocumentationMarkup.DEFINITION_END)

                // Generate Mdn Documentation
                if(UnocssSettingsState.instance.includeMdnDocs) {
                    runReadAction {
                        val cssElementFactory = CssElementFactory.getInstance(cssFile.project)
                        val tempElement = cssElementFactory
                            .createStylesheet(cssFile.text, CSSLanguage.INSTANCE)
                            .childrenOfTypeDeeply<CssDeclaration>()
                        val mdnDocumentations = tempElement.mapNotNull {
                            MdnDocumentationUtil.getMdnDocumentation(it, null)
                        }
                        mdnDocumentations.forEach {
                            append(it.getDocumentation(false))
                        }
                    }
                }

                append(DocumentationMarkup.CONTENT_START)
                val colors = parseColors(result.css)
                if (colors.isNotEmpty()) {
                    val color = colors.first().toHex()
                    val style = "display: inline-block; height: 16px; width: 16px; background-color: $color; border-radius: 4px"
                    append("<div style=\"$style\"></div>")
                }

                append("Generated by Unocss")
                append(DocumentationMarkup.CONTENT_END)
            })
        }
    }

    private fun resolveRemToPx(css: String): String {
        val settingsState = UnocssSettingsState.instance
        if (css.isBlank()) return css

        val remToPxRatio = if (settingsState.remToPxPreview) {
            settingsState.remToPxRatio
        } else {
            -1.0
        }

        if (remToPxRatio < 1) return css
        var index = 0
        val output = StringBuilder()
        while (index < css.length) {
            val rem = remRE.find(css.substring(index)) ?: break
            val px = """ /* ${rem.value.substring(0, rem.value.length - 4).toFloat() * remToPxRatio}px */"""
            val end = index + rem.range.first + rem.value.length
            output.append(css.substring(index, end))
            output.append(px)
            index = end
        }
        output.append(css.substring(index))
        return output.toString()
    }
}

class UnocssThemeConfigDocumentTarget(
    private val targetElement: PsiElement?,
) : DocumentationTarget {

    private val themeConfigPath = targetElement?.text?.trim('"', '\'') ?: ""

    override fun computePresentation(): TargetPresentation {
        return TargetPresentation
            .builder("Unocss Document")
            .presentation()
    }

    override fun createPointer(): Pointer<out DocumentationTarget> {
        val pointer = targetElement?.createSmartPointer()
        return Pointer {
            UnocssThemeConfigDocumentTarget(pointer?.dereference())
        }
    }

    override fun computeDocumentation(): DocumentationResult {
        return DocumentationResult.asyncDocumentation doc@{
            if (targetElement == null) {
                return@doc null
            }

            val configValue = UnocssConfigManager.getThemeValue(themeConfigPath) ?: return@doc null

            DocumentationResult.documentation(buildString {
                append(DocumentationMarkup.DEFINITION_START)
                append("<code>")
                appendHighlightedCss(targetElement.project, configValue)
                append("</code>")
                append(DocumentationMarkup.DEFINITION_END)

                append(DocumentationMarkup.CONTENT_START)
                val color = parseHexColor(configValue)
                if (color != null) {
                    val style =
                        "display: inline-block; height: 16px; width: 16px; background-color: $configValue"
                    append("<div style=\"$style\"></div>")
                }

                append("Unocss Config Theme")
                append(DocumentationMarkup.CONTENT_END)
            })
        }
    }
}

class UnocssThemeScreenDocumentTarget(
    private val targetElement: PsiElement?,
) : DocumentationTarget {

    override fun computePresentation(): TargetPresentation {
        return TargetPresentation
            .builder("Unocss Document")
            .presentation()
    }

    override fun createPointer(): Pointer<out DocumentationTarget> {
        val pointer = targetElement?.createSmartPointer()
        return Pointer {
            UnocssThemeScreenDocumentTarget(pointer?.dereference())
        }
    }

    override fun computeDocumentation(): DocumentationResult {
        return DocumentationResult.asyncDocumentation doc@{
            if (targetElement == null) {
                return@doc null
            }
            val match = breakpointRE.matchEntire(targetElement.text) ?: return@doc null
            val prefix = match.groupValues[1]
            val breakpointName = match.groupValues[2]

            val doc = computeScreenBreakpointsDoc(prefix, breakpointName) ?: return@doc null

            DocumentationResult.documentation(buildString {
                append(DocumentationMarkup.DEFINITION_START)
                append("<code>")
                if (doc == UNDEFINED_BREAKPOINT) {
                    append("Unable to find breakpoint: $breakpointName")
                } else {
                    appendHighlightedCss(targetElement.project, doc)
                }
                append("</code>")
                append(DocumentationMarkup.DEFINITION_END)

                append(DocumentationMarkup.CONTENT_START)
                append("Unocss Config Breakpoints")
                append(DocumentationMarkup.CONTENT_END)
            })
        }
    }

    private fun computeScreenBreakpointsDoc(
        prefix: String,
        breakpointName: String
    ): String? {
        val breakpointsConf = UnocssConfigManager.theme["breakpoints"] ?: return null
        if (!breakpointsConf.isJsonObject) {
            return null
        }
        val breakpointsObj = breakpointsConf.asJsonObject
        val breakpoints = breakpointsObj.keySet()
            .filter { breakpointsObj[it].isJsonPrimitive }
            .mapIndexed { index, key ->
                BreakpointEntry(key, breakpointsObj[key].asJsonPrimitive.asString, index)
            }

        val (_, size, index) = breakpoints.find { it.point == breakpointName }
            ?: return UNDEFINED_BREAKPOINT
        return when (prefix) {
            "lt" -> "@media (max-width: ${calcMaxWidthBySize(size)})"
            "at" -> {
                if (index < breakpoints.lastIndex) {
                    "@media (min-width: $size) and (max-width: ${calcMaxWidthBySize(breakpoints[index + 1].size)})"
                } else {
                    "@media (min-width: ${size})"
                }
            }

            else -> "@media (min-width: ${size})"
        }
    }

    private fun calcMaxWidthBySize(size: String): String {
        val value = (sizePattern.find(size) ?: return size).groupValues[0]
        val unit = size.substring(value.length)
        return try {
            val maxWidth = value.toDouble() - 0.1
            "${maxWidth}${unit}"
        } catch (e: NumberFormatException) {
            size
        }
    }

    private data class BreakpointEntry(val point: String, val size: String, val index: Int)

    companion object {
        private val breakpointRE = Regex("^(?:(lt|at)-)?(\\w+)$")
        private val sizePattern = Regex("^-?[0-9]+\\.?[0-9]*")
        private const val UNDEFINED_BREAKPOINT = "undefined"
    }
}

private suspend fun StringBuilder.appendHighlightedCss(project: Project, css: String) {
    val cssFile: PsiFile = withContext(Dispatchers.EDT) {
        PsiFileFactory.getInstance(project).createFileFromText(CSSLanguage.INSTANCE, css)
    }
    readAction {
        HtmlSyntaxInfoUtil.appendHighlightedByLexerAndEncodedAsHtmlCodeSnippet(
            this,
            cssFile.project,
            CSSLanguage.INSTANCE,
            cssFile.text,
            DocumentationSettings.getHighlightingSaturation(true)
        )
    }
}