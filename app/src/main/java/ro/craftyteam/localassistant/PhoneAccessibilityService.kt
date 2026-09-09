package ro.craftyteam.localassistant

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class PhoneAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    fun snapshot(maxItems: Int = 45): String {
        val root = rootInActiveWindow ?: return "No active accessibility window."
        val items = mutableListOf<String>()
        collectNodes(root, items, maxItems)
        return if (items.isEmpty()) "No readable UI elements." else items.distinct().joinToString("\n").take(1400)
    }

    fun tapText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val candidates = root.findAccessibilityNodeInfosByText(text)
            .filter { it.isVisibleToUser }
            .sortedByDescending { nodeText(it).equals(text, ignoreCase = true) }

        for (node in candidates) {
            var current: AccessibilityNodeInfo? = node
            var depth = 0
            while (current != null && depth < 6) {
                if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                current = current.parent
                depth++
            }
        }
        return false
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val editable = findEditable(root) ?: return false
        editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scrollForward(): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findScrollable(root) ?: return false
        return scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    fun scrollBackward(): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findScrollable(root) ?: return false
        return scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    private fun collectNodes(node: AccessibilityNodeInfo, output: MutableList<String>, maxItems: Int) {
        if (output.size >= maxItems) return

        if (node.isVisibleToUser) {
            val text = nodeText(node)
            if (text.isNotBlank()) {
                val flags = buildList {
                    if (node.isClickable) add("clickable")
                    if (node.isEditable) add("editable")
                    if (node.isScrollable) add("scrollable")
                }
                output.add(if (flags.isEmpty()) text else "$text [${flags.joinToString(",")}]")
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodes(child, output, maxItems)
            if (output.size >= maxItems) return
        }
    }

    private fun nodeText(node: AccessibilityNodeInfo): String {
        return listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isVisibleToUser && node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditable(child)
            if (found != null) return found
        }
        if (node.isVisibleToUser && node.isEditable) return node
        return null
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isVisibleToUser && node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollable(child)
            if (found != null) return found
        }
        return null
    }

    companion object {
        @Volatile
        var instance: PhoneAccessibilityService? = null
            private set
    }
}
