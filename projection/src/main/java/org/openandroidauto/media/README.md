# Local media pipeline

`MediaEngine` opens a user-selected SAF `content:` URI or a local `file:` URI,
extracts the first audio track, and decodes it with the platform `MediaCodec`.
Already-raw WAV/PCM uses the extractor directly, avoiding an unnecessary raw
codec and a dependency on vendor raw-decoder availability.
The sink receives real decoded PCM: 48 kHz, stereo, signed 16-bit little endian,
normally 960 frames / 3,840 bytes per packet. No `AudioTrack` or phone-speaker
fallback is used. A true return from `PcmSink.write` means the transport accepted
that immutable packet; false means retry with bounded backpressure.

```kotlin
val engine = MediaEngine(context,
    MediaEngine.PcmSink { pcm, timestampUs -> audioOutput.enqueuePcm(pcm, timestampUs) },
    object : MediaEngine.Listener {
        override fun onState(snapshot: MediaEngine.Snapshot) { /* main-thread UI */ }
    })
engine.play(selectedUri)
engine.pause()
engine.resume()
engine.seek(30_000)
engine.stop()
engine.close()
```

The real transport API may accept timestamps differently; the sink receives
monotonic microseconds, whereas `Snapshot.positionMs` is track position.
`playQueue`, `next` and `previous` operate a bounded queue of up to 512 URIs.
Remember persistent read permission when retaining SAF selections after reboot;
the engine does not take new permissions by itself.

The controller must flush/release transport audio focus on pause/stop/seek and
reacquire it before resume. The engine cannot retract PCM already accepted by
the head unit. `setVolume` controls media gain; `setDucking(0.2f)` and restoring
`1f` provide local ducking for guidance or an agent session. They do not change
system volume. Separate simultaneous PCM producers need distinct head-unit audio
channels or a real mixer, not alternating packets on one stream.

For optional installed-engine TTS guidance, use `TextToSpeech.synthesizeToFile`
and feed the completed private WAV URI to a separate `MediaEngine` attached to
the negotiated guidance channel. This avoids playing TTS on the phone and then
pretending it was projected. Synthesis, cleanup, interruption and audio-focus
ownership belong to that controller; they are not implemented by this decoder.

`PcmConverter` has no Android dependencies. The 48 kHz PCM16 path preserves samples
exactly; mono is duplicated to both channels. Other 8–192 kHz mono/stereo PCM is
converted with a 32-tap normalized low-pass polyphase resampler, with retained
history across decoder buffers. PCM8 unsigned, PCM16/24/32 signed, and float are
supported. It refuses partial frames, excessive blocks and multichannel layouts
rather than guessing speaker order. The resampler has 16 input frames of
lookahead; this is not a claim of studio mastering quality.

Compile plus 16 JUnit converter cases passed locally. Cases include chunk-boundary
identity, exact output duration, DC preservation, stereo separation, anti-alias
attenuation, float clipping and explicit bounds.

Actual Pixel/Android17 and independent desktop-receiver checks also passed with
owned files selected through the normal SAF picker. WAV play/pause/resume,
next/previous, seeking and final silence matched expected PCM. A separate
compressed-media run exercised AAC mono48kHz and MP3 stereo through the actual
MediaCodec→48kHz stereo PCM16→projection path. Returned AAC tones changed from
880Hz to1760Hz after seek; automatic queue advancement selected MP3 with440Hz,
then660Hz after seek. Every tested tone exceeded56dB dominance. The receiver
captured2,559,232 PCM bytes, with no packets after the queue ended; Clear queue
then succeeded. No assistant microphone was used. These tests establish device
decoding and received transport PCM, not audible playback or focus behavior on a
physical head unit.

HTTP/HLS/DASH, DRM, external-app session control, equalizers and crossfade are not
implemented. Local files keep buffering, privacy and cancellation boundaries
clear while the real projection transport is being tested.

One worker owns native decoder resources and one pending track can replace it.
Stop/seek/generation changes cancel future writes immediately; Android native
provider/decoder calls themselves have platform-defined return times. The engine
does not spawn an unbounded replacement thread when a provider stalls.
