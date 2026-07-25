package org.msgat.core.call

import android.util.Log

object OpusCodecHelper {
    private const val TAG = "OpusCodecHelper"

    /**
     * Modifies the SDP string to prefer Opus codec and tune its parameters:
     * - Force Opus to be the first codec in m=audio payload types
     * - maxaveragebitrate=32000 (compressing bitrate to 16~32 kbps)
     * - useinbandfec=1 (in-band Forward Error Correction for weak networks)
     * - usedtx=1 (Discontinuous Transmission for silence bandwidth saving)
     * - stereo=0 (mono voice optimization)
     * - sprop-maxcapturerate=16000 (16kHz voice bandwidth optimization)
     */
    fun modifySdpForOpus(sdpDescription: String, maxBitrateKbps: Int = 32): String {
        try {
            var lines = sdpDescription.split("\r\n").toMutableList()
            if (lines.size == 1) {
                lines = sdpDescription.split("\n").toMutableList()
            }

            // Find Opus payload type (typically 111)
            var opusPayloadType: String? = null
            val opusRtpMapPattern = Regex("""a=rtpmap:(\d+)\s+opus/48000/2""", RegexOption.IGNORE_CASE)

            for (line in lines) {
                val match = opusRtpMapPattern.find(line)
                if (match != null) {
                    opusPayloadType = match.groupValues[1]
                    break
                }
            }

            if (opusPayloadType == null) {
                Log.w(TAG, "Opus codec payload type not found in SDP description.")
                return sdpDescription
            }

            Log.d(TAG, "Found Opus payload type: $opusPayloadType")

            // 1. Reorder m=audio line to place Opus payload type first
            for (i in lines.indices) {
                if (lines[i].startsWith("m=audio ")) {
                    val parts = lines[i].split(" ").toMutableList()
                    if (parts.size >= 4) {
                        val mediaHeader = parts.subList(0, 3)
                        val codecs = parts.subList(3, parts.size)
                        codecs.remove(opusPayloadType)
                        codecs.add(0, opusPayloadType)
                        lines[i] = (mediaHeader + codecs).joinToString(" ")
                    }
                    break
                }
            }

            // 2. Add or update fmtp line for Opus
            var fmtpFound = false
            val targetFmtpPrefix = "a=fmtp:$opusPayloadType "
            val opusParams = "minptime=10;useinbandfec=1;usedtx=1;maxaveragebitrate=${maxBitrateKbps * 1000};stereo=0;sprop-maxcapturerate=16000"

            for (i in lines.indices) {
                if (lines[i].startsWith(targetFmtpPrefix)) {
                    lines[i] = "$targetFmtpPrefix$opusParams;${lines[i].removePrefix(targetFmtpPrefix)}"
                    fmtpFound = true
                    break
                }
            }

            if (!fmtpFound) {
                // Insert fmtp line right after a=rtpmap line for Opus
                for (i in lines.indices) {
                    if (lines[i].startsWith("a=rtpmap:$opusPayloadType")) {
                        lines.add(i + 1, "$targetFmtpPrefix$opusParams")
                        break
                    }
                }
            }

            val result = lines.joinToString("\r\n")
            Log.d(TAG, "Successfully optimized SDP for Opus codec ($maxBitrateKbps kbps, FEC enabled, DTX enabled).")
            return result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to modify SDP for Opus optimization", e)
            return sdpDescription
        }
    }
}
