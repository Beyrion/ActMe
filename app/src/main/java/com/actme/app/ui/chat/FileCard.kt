package com.actme.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
    }
}

data class FileTypeStyle(
    val icon: ImageVector,
    val tint: Color
)

fun fileTypeStyle(extension: String): FileTypeStyle {
    val ext = extension.lowercase()
    return when {
        ext in setOf("xlsx", "xls", "xlsm", "csv") -> FileTypeStyle(
            Icons.Filled.TableChart,
            Color(0xFF1B6D4A)
        )
        ext == "pdf" -> FileTypeStyle(
            Icons.Filled.PictureAsPdf,
            Color(0xFFD32F2F)
        )
        ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg") -> FileTypeStyle(
            Icons.Filled.Image,
            Color(0xFF7B1FA2)
        )
        ext in setOf("py", "java", "kt", "cpp", "c", "h", "js", "ts", "sh") -> FileTypeStyle(
            Icons.Filled.Code,
            Color(0xFF1565C0)
        )
        ext == "json" -> FileTypeStyle(
            Icons.Filled.DataObject,
            Color(0xFF5D4037)
        )
        ext in setOf("md", "markdown", "txt", "log") -> FileTypeStyle(
            Icons.Filled.Description,
            Color(0xFF546E7A)
        )
        ext == "html" || ext == "htm" -> FileTypeStyle(
            Icons.Filled.Code,
            Color(0xFFE65100)
        )
        else -> FileTypeStyle(
            Icons.AutoMirrored.Filled.InsertDriveFile,
            Color(0xFF78909C)
        )
    }
}

@Composable
fun FileCard(
    fileName: String,
    filePath: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val file = File(filePath)
    val fileSize = if (file.exists()) file.length() else 0L
    val extension = file.extension

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        modifier = modifier.clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val style = fileTypeStyle(extension)
            Icon(
                imageVector = style.icon,
                contentDescription = extension,
                tint = style.tint,
                modifier = Modifier.size(20.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium
                )
                if (fileSize > 0) {
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = formatFileSize(fileSize),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1
                    )
                }
            }
        }
    }
}
