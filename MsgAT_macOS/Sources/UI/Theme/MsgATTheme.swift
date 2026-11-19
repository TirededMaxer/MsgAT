import SwiftUI

enum AppLanguage: String, CaseIterable {
    case system, english, simplifiedChinese

    var labelKey: String {
        switch self {
        case .system: "follow_system"
        case .english: "english"
        case .simplifiedChinese: "simplified_chinese"
        }
    }

    var localization: String {
        switch self {
        case .system: Bundle.preferredLocalizations(from: ["en", "zh-Hans"], forPreferences: Locale.preferredLanguages).first ?? "en"
        case .english: "en"
        case .simplifiedChinese: "zh-Hans"
        }
    }
}

struct InterfaceStrings {
    private let localized: Bundle?
    let locale: Locale
    private static let english = load("en")

    init(_ language: AppLanguage = .system) {
        localized = Self.load(language.localization)
        locale = language == .system ? .current : Locale(identifier: language.localization)
    }

    func callAsFunction(_ key: String) -> String {
        let fallback = Self.english?.localizedString(forKey: key, value: key, table: "Interface") ?? key
        return localized?.localizedString(forKey: key, value: fallback, table: "Interface") ?? fallback
    }

    func format(_ key: String, _ arguments: CVarArg...) -> String {
        String(format: self(key), arguments: arguments)
    }

    static func current() -> InterfaceStrings {
        let saved = RuntimePaths.defaults.string(forKey: MsgATTheme.languagePreference)
        return InterfaceStrings(saved.flatMap(AppLanguage.init(rawValue:)) ?? .system)
    }

    private static func load(_ localization: String) -> Bundle? {
        #if SWIFT_PACKAGE
        let resources = Bundle.module
        #else
        let resources = Bundle.main
        #endif
        return resources.url(forResource: localization, withExtension: "lproj").flatMap { Bundle(url: $0) }
    }
}

private struct InterfaceStringsKey: EnvironmentKey {
    static let defaultValue = InterfaceStrings()
}

enum AppThemeMode: String, CaseIterable {
    case system, light, dark

    var labelKey: String {
        switch self {
        case .system: "follow_system"
        case .light: "light"
        case .dark: "dark"
        }
    }

    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}

struct MessagingColors {
    let timeline: Color
    let chrome: Color
    let sidebar: Color
    let selection: Color
    let divider: Color
    let selectionMark: Color

    init(timeline: UInt32, chrome: UInt32, sidebar: UInt32, selection: UInt32, divider: UInt32, selectionMark: UInt32) {
        func color(_ rgb: UInt32) -> Color {
            Color(red: Double((rgb >> 16) & 255) / 255,
                  green: Double((rgb >> 8) & 255) / 255,
                  blue: Double(rgb & 255) / 255)
        }
        self.timeline = color(timeline)
        self.chrome = color(chrome)
        self.sidebar = color(sidebar)
        self.selection = color(selection)
        self.divider = color(divider)
        self.selectionMark = color(selectionMark)
    }
}

public enum MsgATTheme {
    static let themePreference = "themeMode"
    static let languagePreference = "language"
    static let chatTextPreference = "chatTextPercentage"
    static let chatTextPercentages = [85, 100, 115, 130]

    private static let lightMessagingColors = MessagingColors(
        timeline: 0xF6F8F7, chrome: 0xFFFFFF, sidebar: 0xEDF2EF,
        selection: 0xE4EFE9, divider: 0xD5DFDA, selectionMark: 0x126741
    )
    private static let darkMessagingColors = MessagingColors(
        timeline: 0x171E1B, chrome: 0x222B27, sidebar: 0x1C2521,
        selection: 0x2D4238, divider: 0x3E4E47, selectionMark: 0x8DDAB1
    )

    static func messagingColors(for colorScheme: ColorScheme) -> MessagingColors {
        colorScheme == .dark ? darkMessagingColors : lightMessagingColors
    }

    static func chatTextPercentage(_ value: Int) -> Int {
        chatTextPercentages.contains(value) ? value : 100
    }
    public static let connectionDotColor = Color(red: 29/255.0, green: 150/255.0, blue: 86/255.0)
    public static let connectionColor = Color(red: 22/255.0, green: 131/255.0, blue: 74/255.0)
    public static let primaryColor = Color(red: 16/255.0, green: 185/255.0, blue: 129/255.0) // 0xFF10B981
    public static let backgroundDark = Color(red: 18/255.0, green: 18/255.0, blue: 18/255.0)
    public static let surfaceDark = Color(red: 30/255.0, green: 30/255.0, blue: 36/255.0)
    public static let surfaceVariantDark = Color(red: 58/255.0, green: 58/255.0, blue: 60/255.0)

    public static let myBubbleColor = Color(red: 220/255.0, green: 239/255.0, blue: 228/255.0)
    static let myBubbleDarkColor = Color(red: 32/255.0, green: 70/255.0, blue: 56/255.0)

    public static let peerBubbleColor = Color.white
    static let peerBubbleDarkColor = Color(red: 40/255.0, green: 51/255.0, blue: 46/255.0)

    public static let successColor = Color(red: 16/255.0, green: 185/255.0, blue: 129/255.0)
    public static let warningColor = Color.orange
    public static let errorColor = Color(red: 239/255.0, green: 68/255.0, blue: 68/255.0)
}

private struct ChatTextScaleKey: EnvironmentKey {
    static let defaultValue: CGFloat = 1
}

extension EnvironmentValues {
    var interfaceStrings: InterfaceStrings {
        get { self[InterfaceStringsKey.self] }
        set { self[InterfaceStringsKey.self] = newValue }
    }

    var chatTextScale: CGFloat {
        get { self[ChatTextScaleKey.self] }
        set { self[ChatTextScaleKey.self] = newValue }
    }
}
