#import "include/MsgATNativeAudio.h"
#import <WebRTC/RTCDefaultVideoEncoderFactory.h>
#import <WebRTC/RTCDefaultVideoDecoderFactory.h>

RTCPeerConnectionFactory *MsgATCreateAudioFactory(id<RTCAudioDevice> audioDevice) {
    return [[RTCPeerConnectionFactory alloc]
        initWithEncoderFactory:[[RTCDefaultVideoEncoderFactory alloc] init]
                decoderFactory:[[RTCDefaultVideoDecoderFactory alloc] init]
                   audioDevice:audioDevice];
}
