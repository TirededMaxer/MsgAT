// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import AppKit
import CoreImage
import ImageIO
import Vision

enum ContactCodes {
    static func verification(_ scan:String)->String {"msgat://verify/"+scan.replacingOccurrences(of:"+",with:"-").replacingOccurrences(of:"/",with:"_").replacingOccurrences(of:"=",with:"")}
    static func image(_ value:String)throws->NSImage{
        guard value.utf8.count<=1024,let filter=CIFilter(name:"CIQRCodeGenerator") else {throw CocoaError(.coderInvalidValue)}
        filter.setValue(Data(value.utf8),forKey:"inputMessage");filter.setValue("M",forKey:"inputCorrectionLevel")
        guard let output=filter.outputImage?.transformed(by:CGAffineTransform(scaleX:8,y:8)),let image=CIContext(options:[.cacheIntermediates:false]).createCGImage(output,from:output.extent) else {throw CocoaError(.fileReadCorruptFile)}
        return NSImage(cgImage:image,size:NSSize(width:image.width,height:image.height))
    }
    static func read(_ url:URL)throws->String{
        let access=url.startAccessingSecurityScopedResource();defer {if access {url.stopAccessingSecurityScopedResource()}}
        guard (try url.resourceValues(forKeys:[.fileSizeKey]).fileSize ?? Int.max)<=16*1024*1024,
              let source=CGImageSourceCreateWithURL(url as CFURL,[kCGImageSourceShouldCache:false] as CFDictionary),
              let image=CGImageSourceCreateThumbnailAtIndex(source,0,[kCGImageSourceCreateThumbnailFromImageAlways:true,kCGImageSourceCreateThumbnailWithTransform:true,kCGImageSourceThumbnailMaxPixelSize:2048] as CFDictionary) else {throw CocoaError(.fileReadCorruptFile)}
        let request=VNDetectBarcodesRequest();request.symbologies=[.qr]
        try VNImageRequestHandler(cgImage:image,options:[:]).perform([request])
        let codes=Set((request.results ?? []).compactMap(\.payloadStringValue))
        guard codes.count==1,let result=codes.first,result.utf8.count<=1024 else {throw SecurityCore.Failure.operation("Select an image with one readable QR code")};return result
    }
}
struct ContactCodeImage:View {
    @Environment(\.interfaceStrings) private var interfaceText
    let value:String
    @State private var image:NSImage?;@State private var failed=false
    var body: some View {
        Group {
            if let image {
                Image(nsImage: image).interpolation(.none).resizable().scaledToFit()
                    .frame(width: 220, height: 220).padding(24).background(.white)
                    .accessibilityLabel(interfaceText("contact_qr_description"))
            } else if failed {
                Text(interfaceText("contact_qr_unavailable"))
            } else {
                ProgressView()
            }
        }
        .task(id: value) {
            image = nil
            failed = false
            do {
                let generated = try await EncryptedMediaWork.run { try ContactCodes.image(value) }
                if !Task.isCancelled { image = generated }
            } catch {
                if !Task.isCancelled { failed = true }
            }
        }
    }
}
