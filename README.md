# Deadlocks in non-hierarchical CSP

This is a companion project with code for 
[the corresponding blog post](https://medium.com/@elizarov/deadlocks-in-non-hierarchical-csp-e5910d137cc).

The original code based on Kotlin Conf 2018 talk "Kotlin Coroutines in Practice"
([video](https://www.youtube.com/watch?v=a3agLJQ6vt8), [slides](https://speakerdeck.com/elizarov/kotlin-coroutines-in-practice-at-kotlinconf-2018)).

## Code

See the following sources:

| Source | Description | Playground |
| ------ | ----------- | ---------- |
| [DownloaderOriginal.kt](src/DownloaderOriginal.kt)           | The original version from presentation that suffers from deadlock. | [playground](https://tinyurl.com/yasm6els) |
| [Downloader.go](src/Downloader.go)                           | Go version of the same code. | [playground](https://play.golang.org/p/uaaMhdmsVmS) |
| [DownloaderWithMailbox.kt](src/DownloaderWithMailbox.kt)     | Does not use `select`, but actor patter instead. Still deadlocks. | [playground](https://tinyurl.com/y9ru24yo) |
| [DownloaderWithBuffer.kt](src/DownloaderWithBuffer.kt)       | Same as original but with buffers for both `locations` and `contents` channels. Still deadlocks. | [playground](https://tinyurl.com/yd8g6gsa) |
| [DownloaderWithUnlimitedBuffer.kt](src/DownloaderWithUnlimitedBuffer.kt)| Uses unlimited buffer for `contents` channel and seems to work. | [playground](https://tinyurl.com/ycsrdr6g) |
| [DownloaderWithPriority.kt](src/DownloaderWithPriority.kt)   | Gives `contents` channel priority over `references` in downloader coroutine. Still deadlocks. | [playground](https://tinyurl.com/y8kk4gk9) | 
| [DownloaderFixedPriority.kt](src/DownloaderFixedPriority.kt) | Fixes the problem by adjusting priority of `select` in downloader (`contents` first) and using a buffer for `contents` channels. | [playground](https://tinyurl.com/y8bbo5v7) |
| [DownloaderFixedSelect.kt](src/DownloaderFixedSelect.kt)     | Fixes the problem by performing `locations.send` inside `select` in downloader. | [playground](https://tinyurl.com/ycagcomy) |    

## Verifier

There is a formal model-checker that takes a simple description of the corresponding 
communicating final-state machines and exhaustively checks all system states for the presence of deadlocks.
It's code is in [CFSMVerifier.kt](src/verifier/CFSMVerifier.kt) file and there are configuration files for
all examples in its [directory](src/verifier/). The verifier supports plain CFSM using rendezvous channels, 
has built in capability to represent buffered channels with an addition process, and supports priorities
for Kotlin-style `select` expression using addition "transitional" states.

To use it, run `CFSMVerifier` with path(s) to the corresponding `.cfsm` configuration files.


