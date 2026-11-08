// SPDX-License-Identifier: AGPL-3.0-only
namespace MsgAT.Windows.Core.Storage;

public static class RuntimePaths
{
    public static string DataDirectory
    {
        get
        {
            var root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "MsgAT");
#if DEBUG
            return Path.Combine(root, "development");
#else
            return root;
#endif
        }
    }

#if DEBUG
    public const string SingleInstanceName = @"Local\MsgAT.Windows.Development";
#else
    public const string SingleInstanceName = @"Local\MsgAT.Windows.Singleton";
#endif
}
