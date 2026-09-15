import SwiftUI

public enum MsgATTheme {
    public static let primaryColor = Color(red: 16/255.0, green: 185/255.0, blue: 129/255.0) // 0xFF10B981
    public static let backgroundDark = Color(red: 18/255.0, green: 18/255.0, blue: 18/255.0)
    public static let surfaceDark = Color(red: 30/255.0, green: 30/255.0, blue: 36/255.0)
    public static let surfaceVariantDark = Color(red: 58/255.0, green: 58/255.0, blue: 60/255.0)

    // Fresh vibrant green (isMine = true)
    public static let myBubbleColor = Color(red: 16/255.0, green: 185/255.0, blue: 129/255.0) // #10B981 (Fresh Emerald Mint Green)

    // Light Gray for peer bubble (isMine = false)
    public static let peerBubbleColor = Color(red: 228/255.0, green: 228/255.0, blue: 232/255.0) // #E4E4E8 (Light Gray)

    public static let successColor = Color(red: 16/255.0, green: 185/255.0, blue: 129/255.0)
    public static let warningColor = Color.orange
    public static let errorColor = Color(red: 239/255.0, green: 68/255.0, blue: 68/255.0)
}
