# Telekram

A simplified kotlin wrapper for telegrams TDLib based on tdlight-java.  
(Telekram is not stable. Use with caution)

## Supported Features

These features are currently supported:
* Get a list of all chats
* Get the chat history to one chat

## Planned Features

These features should be focused for 1.0 release: 
* Send messages to chats
* Subscribe to single chat to react on new messages live
* Subscribe to all chats to react on new messages live

## Requirements

You only need to add this library to your project to get started.

These platforms are currently supported:
* Windows (amd64)
* MacOS (amd64)
* Linux (amd64, aarch64, x86, armv6, armv7, ppc64le)

These platforms are currently **NOT** supported
* Apple Silicon M1 processors (see [Issue in tdlight-java](https://github.com/tdlight-team/tdlight-java/issues/28))

## Getting Started

### Install

TODO: Push to jitpack.io + Add Gradle Snippet to README.md

### Create Telekram Instance

First you have to create a telegram application: https://core.telegram.org/api/obtaining_api_id  
=> From there you get a valid `apiId` and `apiHash`

```kotlin
val telekram = Telekram(
    apiId = //TODO: Replace with App-Id,
    apiHash = //TODO: Replace with App-Hash,,
    phoneNumberProvider = {
        // If you embed the library in a gui application (e.g. Android) this might be modified
        println("Enter telephone number: ")
        Scanner(System.`in`).nextLine()
    },
    submitCodeProvider = {
        // If you embed the library in a gui application (e.g. Android) this might be modified
        println("Enter submit code number: ")
        Scanner(System.`in`).nextLine()
    }
)
```

### Get a list of all chats

```kotlin
when (val result = telekram.getChats()) {
    is GetChatsResult.Ok -> {
        val chats = result.chats
        println("Fetched ${chats.size} chats")

        chats.forEach { chat ->
            println("The last message in chat '${chat.chatTitle}' is '${chat.lastMessage.text}'")
        }
    }
    is GetChatsResult.Error -> {
        println("Error while getting chats: ${result.errorMessage}")
    }
}
```

## Complete Examples

+ [Simple example that lists telegram chats and messages](ExampleBasic/src/main/kotlin/com/maaxgr/telekram/exbasic/Example.kt)
+ [CLI Tool to read new telegram messages](ExampleBasic/src/main/kotlin/com/maaxgr/telekram/exbasic/Example.kt)

## In Depth Documentation

### Info to programming style

* All exposed methods can be called via a `Telekram`-Instance
* All method results are based on kotlin sealed methods. There is an Ok- and an Error-Result