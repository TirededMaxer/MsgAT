import SwiftUI
import AppKit

public struct AvatarView: View {
    let avatarPath: String?
    let fallbackText: String
    let size: CGFloat
    let cornerRadius: CGFloat
    let fallbackSymbol: String?

    public init(
        avatarPath: String?,
        fallbackText: String,
        size: CGFloat = 40,
        cornerRadius: CGFloat = 10,
        fallbackSymbol: String? = nil
    ) {
        self.avatarPath = avatarPath
        self.fallbackText = fallbackText
        self.size = size
        self.cornerRadius = cornerRadius
        self.fallbackSymbol = fallbackSymbol
    }

    @State private var image: NSImage?
    @State private var retry=0
    private struct LoadKey: Equatable {let path: String?;let retry: Int}
    @MainActor private func load() async {
        image=nil
        let path=avatarPath
        let decoded: NSImage?
        do {
            decoded=try await EncryptedMediaWork.run {
                guard let path,!path.isEmpty else {return nil}
                if path.hasPrefix(EncryptedObjectStore.referencePrefix) {
                    guard let media=try EncryptedObjectStore.shared.metadata(path,messageID:"avatar"),media.available,
                          media.size>0,media.size<=512*1024 else {return nil}
                    return try EncryptedObjectStore.shared.image(media,maximumDimension:512)
                }
                return MediaStorageManager.shared.generateImageThumbnail(fileUrl:URL(fileURLWithPath:path),maxDimension:512)
            }
        } catch {decoded=nil}
        if !Task.isCancelled {image=decoded}
    }

    public var body: some View {
        ZStack {
            if let img = image {
                Image(nsImage: img)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
                    .frame(width: size, height: size)
                    .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
            } else {
                RoundedRectangle(cornerRadius: cornerRadius)
                    .fill(Color(white: fallbackSymbol == nil ? 51/255 : 68/255))
                    .frame(width: size, height: size)
                    .overlay {
                        if let symbol = fallbackSymbol {
                            Image(systemName: symbol).font(.system(size: 24)).foregroundColor(.white)
                        } else {
                            Text(fallbackText.prefix(1).uppercased())
                                .font(.system(size: size * 0.42, weight: .bold))
                                .foregroundColor(.white)
                        }
                    }
            }
        }
        .task(id:LoadKey(path:avatarPath,retry:retry)) {await load()}
        .onReceive(NotificationCenter.default.publisher(for:MsgDao.didChangeNotification)) {_ in
            if image==nil,avatarPath?.hasPrefix(EncryptedObjectStore.referencePrefix)==true {retry &+= 1}
        }
    }
}
