package me.rerere.unocssintellij.marker.inlay

import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.hints.ChangeListener
import com.intellij.codeInsight.hints.FactoryInlayHintsCollector
import com.intellij.codeInsight.hints.ImmediateConfigurable
import com.intellij.codeInsight.hints.InlayHintsCollector
import com.intellij.codeInsight.hints.InlayHintsProvider
import com.intellij.codeInsight.hints.InlayHintsProviderFactory
import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.codeInsight.hints.InlayPresentationFactory
import com.intellij.codeInsight.hints.NoSettings
import com.intellij.codeInsight.hints.ProviderInfo
import com.intellij.codeInsight.hints.SettingsKey
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.codeInsight.hints.presentation.MouseButton
import com.intellij.codeInsight.hints.presentation.ScaleAwarePresentationFactory
import com.intellij.lang.Language
import com.intellij.lang.css.CSSLanguage
import com.intellij.lang.html.HTMLLanguage
import com.intellij.lang.javascript.JavascriptLanguage
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.elementType
import com.intellij.psi.util.parentOfType
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlElementType
import com.intellij.refactoring.suggested.endOffset
import com.intellij.refactoring.suggested.startOffset
import com.intellij.ui.ColorChooserService
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.ColorIcon
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.unocssintellij.UnocssService
import me.rerere.unocssintellij.marker.SVGIcon
import me.rerere.unocssintellij.marker.inlay.UnocssColorPreviewInlayHitsProviderFactory.Meta
import me.rerere.unocssintellij.model.UnocssResolveMeta
import me.rerere.unocssintellij.settings.UnocssSettingsState
import me.rerere.unocssintellij.settings.UnocssSettingsState.ColorAndIconPreviewType.INLAY_HINT
import me.rerere.unocssintellij.util.MatchedPosition
import me.rerere.unocssintellij.util.getMatchedPositions
import me.rerere.unocssintellij.util.isLeafJsLiteral
import me.rerere.unocssintellij.util.isUnocssCandidate
import me.rerere.unocssintellij.util.parseColors
import me.rerere.unocssintellij.util.parseIcons
import me.rerere.unocssintellij.util.toHex
import java.awt.Color
import java.awt.Cursor
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.JPanel

@Suppress("UnstableApiUsage")
class UnocssColorPreviewInlayHitsProviderFactory : InlayHintsProviderFactory {

    object Meta {
        val supportedLanguages = setOf(
            HTMLLanguage.INSTANCE,
            CSSLanguage.INSTANCE,
            JavascriptLanguage.INSTANCE,
        )

        val settingsKey = SettingsKey<NoSettings>("unocss.colorPreview.hints")
    }

    override fun getProvidersInfo() = Meta.supportedLanguages.map {
        ProviderInfo(it, UnocssColorPreviewInlayHintsProvider)
    }

    override fun getLanguages(): Iterable<Language> {
        return Meta.supportedLanguages
    }

    override fun getProvidersInfoForLanguage(language: Language): List<InlayHintsProvider<out Any>> {
        return Meta.supportedLanguages.filter { language.isKindOf(it) }
            .map { UnocssColorPreviewInlayHintsProvider }
    }
}

@Suppress("UnstableApiUsage")
object UnocssColorPreviewInlayHintsProvider : InlayHintsProvider<NoSettings> {

    override val name: String = "Unocss Color Preview"
    override val key: SettingsKey<NoSettings> = Meta.settingsKey
    override val previewText: String = "<div text-red class=\"bg-blue-400\"></div>"
    override val description: String = "Unocss color preview on token"

    override fun createConfigurable(settings: NoSettings): ImmediateConfigurable {
        return object : ImmediateConfigurable {
            override fun createComponent(listener: ChangeListener) = JPanel()
        }
    }

    override fun createSettings(): NoSettings = NoSettings()

    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: NoSettings,
        sink: InlayHintsSink
    ): InlayHintsCollector? {
        val project = file.project
        return if (!DumbService.isDumb(project) && !project.isDefault) {
            val unocssService = project.service<UnocssService>()
            val virtualFile = file.virtualFile
            if (virtualFile == null || !virtualFile.isInLocalFileSystem) {
                return null
            }

            val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
            val fileContent = document.text
            if (StringUtil.isEmptyOrSpaces(fileContent)) {
                return null
            }

            val resolveResult = runBlockingCancellable {
                withTimeoutOrNull(800) {
                    unocssService.resolveAnnotations(file.virtualFile, fileContent)
                }
            } ?: return null

            MyCollector(editor, getMatchedPositions(fileContent, resolveResult))
        } else null
    }

    class MyCollector(editor: Editor, private val matchedPositions: List<MatchedPosition>) :
        FactoryInlayHintsCollector(editor) {

        private val scaleAwarePresentationFactory = ScaleAwarePresentationFactory(editor, factory)

        override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
            val settingsState = UnocssSettingsState.of(element.project)
            if (settingsState.colorAndIconPreviewType != INLAY_HINT) {
                return false
            }
            element.node ?: return false
            if (!element.isUnocssCandidate()) {
                return true
            }

            matchedPositions
                .filter { it.start >= element.startOffset && it.end <= element.endOffset }
                .mapNotNull {
                    when (element.elementType) {
                        XmlElementType.XML_NAME -> {
                            // make sure it has no attr value
                            if (element.nextSibling == null) {
                                UnocssResolveMeta(element, it.text, null) to it.start
                            } else null
                        }

                        XmlElementType.XML_ATTRIBUTE_VALUE_TOKEN -> {
                            element.parentOfType<XmlAttributeValue>()?.let { attrValueEle ->
                                val xmlName = attrValueEle.parent.firstChild.text
                                val offset = element.startOffset
                                val attrValue = element.text.substring(it.start - offset, it.end - offset)

                                UnocssResolveMeta(element, xmlName, attrValue) to it.start
                            }
                        }

                        else -> UnocssResolveMeta(element, it.text, null) to it.start
                    }
                }
                .forEach { (resolveMeta, startOffset) ->
                    val css = resolveMeta.resolveCss()?.css ?: return@forEach

                    val colorValue = parseColors(css)
                    if (colorValue.isNotEmpty()) {
                        sink.addInlineElement(
                            startOffset,
                            true,
                            buildColorPresentation(colorValue.first(), editor, resolveMeta, startOffset),
                            false
                        )
                    }

                    val iconValue = parseIcons(css)
                    if (iconValue != null) {
                        buildIconPresentation(iconValue, editor)?.let {
                            sink.addInlineElement(
                                startOffset,
                                true,
                                it,
                                false
                            )
                        }
                    }
                }

            return true
        }

        private val generateNewValue = { oldVal: String, color: Color, startOffset: Int ->
            when {
                oldVal.startsWith("bg-") -> "bg-[${color.toHex(true)}]"
                oldVal.startsWith("text-") -> "text-[${color.toHex(true)}]"
                oldVal.startsWith("border-") -> "border-[${color.toHex(true)}]"
                else -> {
                    // Unknown unocss color pattern
                    HintManager.getInstance().showErrorHint(
                        editor,
                        "Could not generate, please replace it manually: ${color.toHex(true)}",
                        startOffset,
                        startOffset,
                        HintManager.ABOVE,
                        HintManager.HIDE_BY_ANY_KEY or HintManager.HIDE_BY_OTHER_HINT,
                        5000
                    )
                    null
                }
            }
        }

        private fun buildColorPresentation(
            color: JBColor,
            editor: Editor,
            meta: UnocssResolveMeta,
            startOffset: Int
        ): InlayPresentation {
            val padding = InlayPresentationFactory.Padding(2, 2, 2, 2)
            val bgColor = editor.colorsScheme
                .getColor(DefaultLanguageHighlighterColors.INLINE_REFACTORING_SETTINGS_DEFAULT)
            val roundedCorners = InlayPresentationFactory.RoundedCorners(6, 6)

            val clickListener: (MouseEvent, Point) -> Unit = { event: MouseEvent, _: Point ->
                val point = RelativePoint(event.component, event.point)
                val startOffsetOfElement = startOffset - meta.bindElement.startOffset
                val initMeta = meta.copy()
                val initElementText = meta.bindElement.text

                ColorChooserService.instance.showPopup(
                    project = editor.project!!,
                    currentColor = color,
                    listener = { color, _ ->
                        // println("color: $color, ${meta.bindElement}, ${meta.attrName}:${meta.attrValue}, $startOffset")
                        runWriteAction {
                            when {
                                // xml attribute value token
                                initMeta.bindElement.elementType == XmlElementType.XML_ATTRIBUTE_VALUE_TOKEN -> {
                                    val oldValue = meta.attrValue ?: return@runWriteAction
                                    val newValue = generateNewValue(oldValue, color, startOffset)
                                        ?: return@runWriteAction

                                    val oldAttValue = initElementText ?: return@runWriteAction
                                    val stopIndex =
                                        oldAttValue.indexOfAny(charArrayOf(' ', '"', '\'', '\n'), startOffsetOfElement)
                                            .let { if (it == -1) oldAttValue.length else it }
                                    val newAttrValue = oldAttValue.substring(
                                        0,
                                        startOffsetOfElement
                                    ) + newValue + oldAttValue.substring(stopIndex)

                                    if (meta.bindElement is LeafPsiElement) {
                                        meta.bindElement = (meta.bindElement as LeafPsiElement)
                                            .replaceWithText(newAttrValue) as PsiElement
                                    }
                                }

                                // JS String Leaf
                                initMeta.bindElement.isLeafJsLiteral() || (initMeta.bindElement is LeafPsiElement && initMeta.bindElement.elementType.toString()
                                    .startsWith("JS:STRING")) -> {
                                    val oldValue = meta.attrName
                                    val newValue =
                                        generateNewValue(oldValue, color, startOffset) ?: return@runWriteAction

                                    val oldAttValue = initElementText ?: return@runWriteAction
                                    val stopIndex =
                                        oldAttValue.indexOfAny(charArrayOf(' ', '"', '\'', '\n'), startOffsetOfElement)
                                    val newAttrValue = oldAttValue.substring(
                                        0,
                                        startOffsetOfElement
                                    ) + newValue + oldAttValue.substring(stopIndex)

                                    if (meta.bindElement is LeafPsiElement) {
                                        meta.bindElement =
                                            (meta.bindElement as LeafPsiElement).replaceWithText(newAttrValue) as PsiElement
                                    }
                                }

                                // 整个元素就是一个属性
                                initElementText == meta.attrName -> {
                                    val oldValue = meta.attrName
                                    val newValue = generateNewValue(oldValue, color, startOffset)
                                        ?: return@runWriteAction

                                    if (meta.bindElement is LeafPsiElement) {
                                        meta.bindElement = (meta.bindElement as LeafPsiElement)
                                                .replaceWithText(newValue) as PsiElement
                                    } else {
                                        HintManager.getInstance().showErrorHint(
                                            editor,
                                            "Not a leaf element: ${meta.bindElement.elementType}, please replace it manually: $newValue",
                                            startOffset,
                                            startOffset,
                                            HintManager.ABOVE,
                                            HintManager.HIDE_BY_ANY_KEY,
                                            5000
                                        )
                                    }
                                }

                                else -> {
                                    println(
                                        meta.bindElement.javaClass.name
                                    )
                                    HintManager.getInstance().showErrorHint(
                                        editor,
                                        "Not supported yet: ${meta.bindElement.elementType}}, please replace it manually: ${color.toHex(true)}",
                                        startOffset,
                                        startOffset,
                                        HintManager.ABOVE,
                                        HintManager.HIDE_BY_ANY_KEY,
                                        5000
                                    )
                                }
                            }
                        }
                    },
                    location = point,
                    showAlpha = true
                )
            }

            val scaleFactory = scaleAwarePresentationFactory
            val base = scaleFactory.lineCentered(
                scaleFactory.container(
                    scaleFactory.smallScaledIcon(ColorIcon(18, color, 4)),
                    padding,
                    roundedCorners,
                    bgColor
                )
            )
            val inset = scaleFactory.inset(factory.text(""), 0, 4, 0, 0)
            return factory.onClick(
                factory.withCursorOnHover(
                    factory.seq(base, inset),
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                ),
                MouseButton.Left,
                clickListener
            )
        }

        private fun buildIconPresentation(icon: String, editor: Editor): InlayPresentation? {
            val svgIcon = SVGIcon.tryGetIcon(icon, 18).getOrNull() ?: return null
            val padding = InlayPresentationFactory.Padding(2, 2, 2, 2)
            val bgColor = editor.colorsScheme
                .getColor(DefaultLanguageHighlighterColors.INLINE_REFACTORING_SETTINGS_DEFAULT)
            val roundedCorners = InlayPresentationFactory.RoundedCorners(6, 6)

            val scaleFactory = scaleAwarePresentationFactory
            val base = scaleFactory.lineCentered(
                scaleFactory.container(
                    scaleFactory.icon(svgIcon),
                    padding,
                    roundedCorners,
                    bgColor
                )
            )
            val inset = scaleFactory.inset(factory.text(""), 0, 4, 0, 0)
            return factory.seq(base, inset)
        }
    }
}

private fun ColorIcon(size: Int, color: JBColor, arc: Int) =
    ColorIcon(size, size, size, size, color, false, arc)