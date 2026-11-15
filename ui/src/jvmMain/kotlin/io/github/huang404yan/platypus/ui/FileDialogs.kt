package io.github.huang404yan.platypus.ui

import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * 选一个「要保存到哪」的文件（导出整合包、导出清单这类）。
 *
 * 与 [pickDirectory] 同一套理由：Swing 的 JFileChooser 而不是 AWT FileDialog，
 * 因为后者不能设默认文件名、也不能在中文系统上给出中文按钮。
 *
 * 返回值的扩展名不保证对 —— 用户在对话框里可以把文件名改成 `xxx.txt`。
 * 调用方要自己把扩展名补齐，所以这里不替它检查。
 */
internal fun chooseSaveFile(
    title: String,
    suggestedName: String,
    extension: String,
    startDir: File?,
): File? {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    val chooser = JFileChooser()
    chooser.dialogTitle = title
    chooser.fileSelectionMode = JFileChooser.FILES_ONLY
    chooser.isAcceptAllFileFilterUsed = true
    if (extension.isNotBlank()) {
        chooser.fileFilter = FileNameExtensionFilter(extension.uppercase() + " 文件 (*." + extension + ")", extension)
    }
    if (startDir != null && startDir.isDirectory) chooser.currentDirectory = startDir
    if (suggestedName.isNotBlank()) chooser.selectedFile = File(suggestedName)
    return if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile
    } else {
        null
    }
}

/** 在系统文件管理器里定位到某个文件（或打开某个目录）；失败不抛。 */
internal fun openInExplorer(path: File?): Boolean = runCatching {
    val target = path ?: return false
    if (!target.exists()) return false
    if (java.awt.Desktop.isDesktopSupported() &&
        java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)
    ) {
        java.awt.Desktop.getDesktop().open(if (target.isDirectory) target else target.parentFile ?: target)
        true
    } else {
        false
    }
}.getOrDefault(false)
