import SwiftUI
import AppKit

public struct AvatarView: View {
    let avatarPath: String?
    let fallbackText: String
    let size: CGFloat
    let cornerRadius: CGFloat

    public init(
        avatarPath: String?,
        fallbackText: String,
        size: CGFloat = 40,
        cornerRadius: CGFloat = 10
    ) {
        self.avatarPath = avatarPath
        self.fallbackText = fallbackText
        self.size = size
        self.cornerRadius = cornerRadius
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
                    .fill(Color.accentColor.opacity(0.18))
                    .frame(width: size, height: size)
                    .overlay(
                        Text(fallbackText.prefix(1).uppercased())
                            .font(.system(size: size * 0.42, weight: .bold))
                            .foregroundColor(.accentColor)
                    )
            }
        }
        .overlay(
            RoundedRectangle(cornerRadius: cornerRadius)
                .stroke(Color.white.opacity(0.1), lineWidth: 0.5)
        )
    }
}
