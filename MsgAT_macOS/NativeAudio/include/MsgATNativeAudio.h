#import "RTCAudioDevice.h"
#import <WebRTC/RTCPeerConnectionFactory.h>
#import <AVFAudio/AVFAudio.h>

NS_ASSUME_NONNULL_BEGIN
RTCPeerConnectionFactory *MsgATCreateAudioFactory(id<RTCAudioDevice> audioDevice);
BOOL MsgATConnectCallInput(AVAudioEngine *engine, AVAudioInputNode *input, AVAudioSinkNode *sink,
                           AVAudioFormat *format);
NS_ASSUME_NONNULL_END
