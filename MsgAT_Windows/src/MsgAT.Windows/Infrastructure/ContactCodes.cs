// SPDX-License-Identifier: AGPL-3.0-only
using System.Windows.Media;
using System.Windows.Media.Imaging;
using ZXing;
using ZXing.Common;
namespace MsgAT.Windows.Infrastructure;

internal static class ContactCodes
{
    internal static string Verification(string scan)=>"msgat://verify/"+scan.Replace('+','-').Replace('/','_').TrimEnd('=');
    internal static BitmapSource Image(string value){
        if(value.Length>1024)throw new InvalidDataException("QR code exceeds its limit.");
        var writer=new BarcodeWriterPixelData{Format=BarcodeFormat.QR_CODE,Options=new EncodingOptions{Width=384,Height=384,Margin=4}};
        var data=writer.Write(value);var result=BitmapSource.Create(data.Width,data.Height,96,96,PixelFormats.Bgra32,null,data.Pixels,data.Width*4);result.Freeze();Array.Clear(data.Pixels);return result;
    }
    internal static string Read(string path){
        using var source=File.OpenRead(path);if(source.Length is <1 or >16*1024*1024)throw new InvalidDataException("Select an image no larger than 16 MiB.");
        var encoded=new byte[checked((int)source.Length)];byte[]? pixels=null;
        try {
            source.ReadExactly(encoded);if(source.ReadByte()!=-1)throw new InvalidDataException("Selected image changed.");
            using var input=new MemoryStream(encoded,writable:false);var original=ImageDecoder.Load(input,2048);
            var image=new FormatConvertedBitmap(original,PixelFormats.Bgra32,null,0);var stride=image.PixelWidth*4;pixels=new byte[checked(stride*image.PixelHeight)];image.CopyPixels(pixels,stride,0);
            var reader=new BarcodeReaderGeneric{Options=new DecodingOptions{PossibleFormats=[BarcodeFormat.QR_CODE],TryHarder=true}};
            var result=reader.Decode(pixels,image.PixelWidth,image.PixelHeight,RGBLuminanceSource.BitmapFormat.BGRA32)?.Text;
            return result is {Length:>0 and <=1024}?result:throw new InvalidDataException("No supported QR code in this image.");
        }finally {Array.Clear(encoded);if(pixels is not null)Array.Clear(pixels);}
    }
}
