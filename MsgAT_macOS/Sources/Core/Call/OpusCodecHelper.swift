import Foundation

public enum OpusCodecHelper {

    /// Modifies the SDP string to prefer Opus codec and tune its parameters (1:1 match with Android):
    /// - Force Opus to be the first codec in m=audio payload types
    /// - maxaveragebitrate=32000 (compressing bitrate to 16~32 kbps)
    /// - useinbandfec=1 (in-band Forward Error Correction for weak networks)
    /// - usedtx=1 (Discontinuous Transmission for silence bandwidth saving)
    /// - stereo=0 (mono voice optimization)
    /// - sprop-maxcapturerate=16000 (16kHz voice bandwidth optimization)
    public static func modifySdpForOpus(sdpDescription: String, maxBitrateKbps: Int = 32) -> String {
        var lines = sdpDescription.components(separatedBy: "\r\n")
        if lines.count <= 1 {
            lines = sdpDescription.components(separatedBy: "\n")
        }

        var opusPayloadType: String? = nil

        // Find Opus payload type (e.g. 111)
        for line in lines {
            if let range = line.range(of: #"a=rtpmap:(\d+)\s+opus/48000/2"#, options: [.regularExpression, .caseInsensitive]) {
                let matched = String(line[range])
                let digits = matched.replacingOccurrences(of: "a=rtpmap:", with: "", options: .caseInsensitive)
                    .split(separator: " ").first
                if let d = digits {
                    opusPayloadType = String(d)
                    break
                }
            } else if line.lowercased().starts(with: "a=rtpmap:") && line.lowercased().contains("opus/48000") {
                let parts = line.dropFirst(9).split(separator: " ")
                if let pt = parts.first {
                    opusPayloadType = String(pt)
                    break
                }
            }
        }

        guard let pt = opusPayloadType else {
            return sdpDescription
        }

        // 1. Reorder m=audio line to place Opus payload type first
        for i in 0..<lines.count {
            if lines[i].starts(with: "m=audio ") {
                let parts = lines[i].components(separatedBy: " ")
                if parts.count >= 4 {
                    let mediaHeader = Array(parts[0..<3])
                    var codecs = Array(parts[3...])
                    if let idx = codecs.firstIndex(of: pt) {
                        codecs.remove(at: idx)
                    }
                    codecs.insert(pt, at: 0)
                    lines[i] = (mediaHeader + codecs).joined(separator: " ")
                }
                break
            }
        }

        // 2. Add or update fmtp line for Opus
        var fmtpFound = false
        let targetFmtpPrefix = "a=fmtp:\(pt) "
        let opusParams = "minptime=10;useinbandfec=1;usedtx=1;maxaveragebitrate=\(maxBitrateKbps * 1000);stereo=0;sprop-maxcapturerate=16000"

        for i in 0..<lines.count {
            if lines[i].starts(with: targetFmtpPrefix) {
                let existing = lines[i].replacingOccurrences(of: targetFmtpPrefix, with: "")
                lines[i] = "\(targetFmtpPrefix)\(opusParams);\(existing)"
                fmtpFound = true
                break
            } else if lines[i].starts(with: "a=fmtp:\(pt)") {
                lines[i] = "\(targetFmtpPrefix)\(opusParams)"
                fmtpFound = true
                break
            }
        }

        if !fmtpFound {
            for i in 0..<lines.count {
                if lines[i].starts(with: "a=rtpmap:\(pt)") {
                    lines.insert("\(targetFmtpPrefix)\(opusParams)", at: i + 1)
                    break
                }
            }
        }

        return lines.joined(separator: "\r\n")
    }
}
