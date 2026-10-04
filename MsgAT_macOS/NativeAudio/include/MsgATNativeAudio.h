#import "RTCAudioDevice.h"
#import <WebRTC/RTCPeerConnectionFactory.h>

NS_ASSUME_NONNULL_BEGIN
RTCPeerConnectionFactory *MsgATCreateAudioFactory(id<RTCAudioDevice> audioDevice);
NS_ASSUME_NONNULL_END
