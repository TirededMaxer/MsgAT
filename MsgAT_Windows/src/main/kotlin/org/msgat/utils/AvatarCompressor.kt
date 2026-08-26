package org.msgat.utils

import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

object AvatarCompressor {

    // Compresses and resizes an arbitrary image file to a compact 1:1 JPEG (< 3KB)
    fun compressAndSaveAvatar(sourceFile: File, destFile: File, maxSize: Int = 128): File {
        val originalImage = ImageIO.read(sourceFile) ?: return sourceFile

        // Center crop to 1:1 square
        val minDim = Math.min(originalImage.width, originalImage.height)
        val x = (originalImage.width - minDim) / 2
        val y = (originalImage.height - minDim) / 2
        val cropped = originalImage.getSubimage(x, y, minDim, minDim)

        // Resize to maxSize x maxSize
        val resized = BufferedImage(maxSize, maxSize, BufferedImage.TYPE_INT_RGB)
        val g = resized.createGraphics()
        g.drawImage(cropped.getScaledInstance(maxSize, maxSize, Image.SCALE_SMOOTH), 0, 0, null)
        g.dispose()

        // Compress to JPEG with 0.85 quality
        val writers = ImageIO.getImageWritersByFormatName("jpg")
        val writer = writers.next()
        val param = writer.defaultWriteParam
        param.compressionMode = ImageWriteParam.MODE_EXPLICIT
        param.compressionQuality = 0.85f

        val outputStream = ByteArrayOutputStream()
        val ios = ImageIO.createImageOutputStream(outputStream)
        writer.output = ios
        writer.write(null, IIOImage(resized, null, null), param)
        writer.dispose()
        ios.close()

        val compressedBytes = outputStream.toByteArray()
        destFile.parentFile?.mkdirs()
        destFile.writeBytes(compressedBytes)
        return destFile
    }
}
