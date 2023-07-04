package me.rerere.unocssintellij.lang

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.psi.tree.IElementType

class UnocssHighlighter : SyntaxHighlighter {
    override fun getHighlightingLexer(): Lexer {
        return UnocssFlexAdapter()
    }

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> {
        return arrayOf(DefaultLanguageHighlighterColors.STRING)
    }
}