#!/usr/bin/env swift

import AVFoundation
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

enum PreviewError: Error, CustomStringConvertible {
    case usage
    case destination
    case frameGeneration

    var description: String {
        switch self {
        case .usage:
            return "usage: create_demo_preview.swift INPUT.mov OUTPUT.gif OUTPUT.jpg"
        case .destination:
            return "could not create an image destination"
        case .frameGeneration:
            return "could not extract a representative video frame"
        }
    }
}

func scaledFrame(
    generator: AVAssetImageGenerator,
    at seconds: Double
) async throws -> CGImage {
    let time = CMTime(seconds: seconds, preferredTimescale: 600)
    return try await generator.image(at: time).image
}

func writeGif(
    generator: AVAssetImageGenerator,
    duration: Double,
    destination: URL
) async throws {
    let framesPerSecond = 2.0
    let frameCount = max(2, Int(floor(duration * framesPerSecond)))
    guard let gif = CGImageDestinationCreateWithURL(
        destination as CFURL,
        UTType.gif.identifier as CFString,
        frameCount,
        nil
    ) else {
        throw PreviewError.destination
    }

    let gifProperties: [CFString: Any] = [
        kCGImagePropertyGIFDictionary: [
            kCGImagePropertyGIFLoopCount: 0
        ]
    ]
    CGImageDestinationSetProperties(gif, gifProperties as CFDictionary)

    let frameProperties: [CFString: Any] = [
        kCGImagePropertyGIFDictionary: [
            kCGImagePropertyGIFDelayTime: 1.0 / framesPerSecond,
            kCGImagePropertyGIFUnclampedDelayTime: 1.0 / framesPerSecond
        ]
    ]

    for index in 0..<frameCount {
        let seconds = min(duration - 0.05, Double(index) / framesPerSecond)
        let image = try await scaledFrame(generator: generator, at: seconds)
        CGImageDestinationAddImage(gif, image, frameProperties as CFDictionary)
    }

    guard CGImageDestinationFinalize(gif) else {
        throw PreviewError.destination
    }
}

func writePoster(
    generator: AVAssetImageGenerator,
    duration: Double,
    destination: URL
) async throws {
    guard let jpeg = CGImageDestinationCreateWithURL(
        destination as CFURL,
        UTType.jpeg.identifier as CFString,
        1,
        nil
    ) else {
        throw PreviewError.destination
    }

    let image = try await scaledFrame(generator: generator, at: duration * 0.55)
    let properties: [CFString: Any] = [
        kCGImageDestinationLossyCompressionQuality: 0.88
    ]
    CGImageDestinationAddImage(jpeg, image, properties as CFDictionary)

    guard CGImageDestinationFinalize(jpeg) else {
        throw PreviewError.destination
    }
}

func run() async throws {
    guard CommandLine.arguments.count == 4 else {
        throw PreviewError.usage
    }

    let input = URL(fileURLWithPath: CommandLine.arguments[1])
    let gifOutput = URL(fileURLWithPath: CommandLine.arguments[2])
    let posterOutput = URL(fileURLWithPath: CommandLine.arguments[3])
    let asset = AVURLAsset(url: input)
    let duration = try await CMTimeGetSeconds(asset.load(.duration))
    guard duration.isFinite, duration > 0 else {
        throw PreviewError.frameGeneration
    }

    let generator = AVAssetImageGenerator(asset: asset)
    generator.appliesPreferredTrackTransform = true
    generator.maximumSize = CGSize(width: 960, height: 600)
    generator.requestedTimeToleranceBefore = CMTime(seconds: 0.08, preferredTimescale: 600)
    generator.requestedTimeToleranceAfter = CMTime(seconds: 0.08, preferredTimescale: 600)

    try FileManager.default.createDirectory(
        at: gifOutput.deletingLastPathComponent(),
        withIntermediateDirectories: true
    )
    try await writeGif(generator: generator, duration: duration, destination: gifOutput)
    try await writePoster(generator: generator, duration: duration, destination: posterOutput)
}

Task {
    do {
        try await run()
        exit(0)
    } catch {
        FileHandle.standardError.write(Data("\(error)\n".utf8))
        exit(1)
    }
}

dispatchMain()
