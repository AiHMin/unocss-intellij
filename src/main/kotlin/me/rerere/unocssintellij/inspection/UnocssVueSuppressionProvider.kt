package me.rerere.unocssintellij.inspection

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoPostFilter
import com.intellij.lang.annotation.HighlightSeverity

private const val UNKNOWN_AT_APPLY_RULE = "Unknown at rule @apply"
private const val UNKNOWN_AT_SCREEN_RULE = "Unknown at rule @screen"

class UnocssVueSuppressionProvider : HighlightInfoPostFilter {
    override fun accept(highlightInfo: HighlightInfo): Boolean {
        if (highlightInfo.severity == HighlightSeverity.WARNING) {
            val description = highlightInfo.description
            if (description.contains(UNKNOWN_AT_APPLY_RULE) || description.contains(UNKNOWN_AT_SCREEN_RULE)) {
                return false
            }
        }
        return true
    }
}