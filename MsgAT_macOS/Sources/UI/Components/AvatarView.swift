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

    private var image: NSImage? {
        guard let path = avatarPath, !path.isEmpty else { return nil }
        let url = URL(fileURLWithPath: path)
        if FileManager.default.fileExists(atPath: url.path) {
            return NSImage(contentsOf: url)
        }
        return nil
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
    }
}
