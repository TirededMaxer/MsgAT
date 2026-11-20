#import "include/MsgATNativeAudio.h"
#import <WebRTC/RTCDefaultVideoEncoderFactory.h>
#import <WebRTC/RTCDefaultVideoDecoderFactory.h>

RTCPeerConnectionFactory *MsgATCreateAudioFactory(id<RTCAudioDevice> audioDevice) {
    return [[RTCPeerConnectionFactory alloc]
        initWithEncoderFactory:[[RTCDefaultVideoEncoderFactory alloc] init]
                decoderFactory:[[RTCDefaultVideoDecoderFactory alloc] init]
                   audioDevice:audioDevice];
}

BOOL MsgATConnectCallInput(AVAudioEngine *engine, AVAudioInputNode *input, AVAudioSinkNode *sink,
                           AVAudioFormat *format) {
    // A hardware route can change between reading its format and connecting the graph.
    // AVFAudio reports that race as an Objective-C exception, outside Swift error handling.
    @try {
        [engine connect:input to:sink format:format];
        [engine prepare];
        return YES;
    } @catch (NSException *exception) {
        if (![exception.name isEqualToString:@"com.apple.coreaudio.avfaudio"]) { @throw; }
        return NO;
    }
}
