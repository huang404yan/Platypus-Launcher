package io.github.huang404yan.platypus.ui

import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

/**
 * 选一个目录（游戏目录、.minecraft 根）。
 *
 * 以前这些输入框只能手打路径 —— 「C:\\Users\\me\\AppData\\Roaming\\.minecraft」
 * 这种路径没人愿意敲第二遍，而且敲错一个字符就静默扫描失败。
 *
 * 用 Swing 的 JFileChooser 而不是 AWT FileDialog：后者在 Windows 上只能选文件，
 * 选目录要先选到目录里的某个文件再回退一层，反人类。
 * 调用发生在 AWT 事件线程上（Compose Desktop 的 UI 线程就是它），模态阻塞是预期行为。
 */
internal fun pickDirectory(initial: String?): File? {
    // 中文界面：不设这个，弹窗标题与按钮会是英文。
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    val chooser = JFileChooser()
    chooser.dialogTitle = "选择游戏目录"
    chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    chooser.isAcceptAllFileFilterUsed = false
    val start = initial?.trim()?.trim('"')?.takeIf { it.isNotBlank() }?.let(::File)
    if (start != null) {
        // 指向已有目录；指向的是文件时退到它的父目录。
        val dir = if (start.isDirectory) start else start.parentFile
        if (dir != null && dir.isDirectory) chooser.currentDirectory = dir
    }
    return if (chooser.showDialog(null, "选这个目录") == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile
    } else {
        null
    }
}
