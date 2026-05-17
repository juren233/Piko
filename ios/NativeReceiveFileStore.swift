import AVFoundation
import Foundation
import Photos
import UIKit

struct NativeReceiveTemporaryFile {
    let finalURL: URL
    let temporaryURL: URL
}

struct NativeReceivedPreparedFile {
    let displayName: String
    let fileType: NativeFileType
    let sizeBytes: Int
    let temporaryURL: URL
}

final class NativeReceiveFileStore {
    func prepareTemporaryFile(fileName: String) -> NativeReceiveTemporaryFile? {
        let directory = receivedDirectory()
        guard (try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)) != nil else {
            return nil
        }
        let finalURL = directory.appendingPathComponent(fileName.sanitizedFileName)
        let temporaryURL = temporaryReceivedURL(fileName: fileName, directory: directory)
        try? FileManager.default.removeItem(at: temporaryURL)
        guard FileManager.default.createFile(atPath: temporaryURL.path, contents: nil) else {
            return nil
        }
        return NativeReceiveTemporaryFile(finalURL: finalURL, temporaryURL: temporaryURL)
    }

    func save(
        _ transfer: NativeReceivedTransfer,
        destinationFor: @escaping (NativeFileType) -> NativeReceiveSaveDestination,
        completion: @escaping (NativeReceiveHistoryItem?) -> Void
    ) {
        let group = DispatchGroup()
        let lock = NSLock()
        var savedFiles: [NativeReceivedFile] = []

        for file in transfer.files {
            guard let preparedFile = prepareTemporaryFile(fileName: file.displayName),
                  (try? file.payloadData.write(to: preparedFile.temporaryURL, options: .atomic)) != nil else {
                continue
            }
            let saveDestination = destinationFor(file.fileType)
            group.enter()
            saveUploadedFile(
                preparedFile,
                displayName: file.displayName,
                fileType: file.fileType,
                sizeBytes: file.sizeBytes,
                destination: saveDestination
            ) { savedFile in
                if let savedFile {
                    lock.lock()
                    savedFiles.append(savedFile)
                    lock.unlock()
                }
                group.leave()
            }
        }

        group.notify(queue: .main) {
            guard let firstFile = savedFiles.first else {
                completion(nil)
                return
            }
            let names = savedFiles.map(\.displayName)
            completion(
                NativeReceiveHistoryItem(
                    title: names.count == 1 ? names[0] : "\(names[0]) + \(names.count - 1) 个文件",
                    subtitle: ByteCountFormatter.string(
                        fromByteCount: Int64(savedFiles.reduce(0) { $0 + $1.sizeBytes }),
                        countStyle: .file
                    ),
                    fileCount: savedFiles.count,
                    primaryFileType: firstFile.fileType,
                    mediaPreviewData: firstFile.mediaPreviewData,
                    files: savedFiles.map(NativeReceiveHistoryFile.init(file:))
                )
            )
        }
    }

    func savePreparedFiles(
        senderName: String,
        files: [NativeReceivedPreparedFile],
        destinationFor: @escaping (NativeFileType) -> NativeReceiveSaveDestination,
        completion: @escaping (NativeReceiveHistoryItem?) -> Void
    ) {
        let group = DispatchGroup()
        let lock = NSLock()
        var savedFiles: [NativeReceivedFile] = []

        for file in files {
            let directory = receivedDirectory()
            guard (try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)) != nil else {
                continue
            }
            let preparedFile = NativeReceiveTemporaryFile(
                finalURL: directory.appendingPathComponent(file.displayName.sanitizedFileName),
                temporaryURL: file.temporaryURL
            )
            let saveDestination = destinationFor(file.fileType)
            group.enter()
            saveUploadedFile(
                preparedFile,
                displayName: file.displayName,
                fileType: file.fileType,
                sizeBytes: file.sizeBytes,
                destination: saveDestination
            ) { savedFile in
                if let savedFile {
                    lock.lock()
                    savedFiles.append(savedFile)
                    lock.unlock()
                }
                group.leave()
            }
        }

        group.notify(queue: .main) {
            guard let firstFile = savedFiles.first else {
                completion(nil)
                return
            }
            let names = savedFiles.map(\.displayName)
            completion(
                NativeReceiveHistoryItem(
                    title: names.count == 1 ? names[0] : "\(names[0]) + \(names.count - 1) 个文件",
                    subtitle: ByteCountFormatter.string(
                        fromByteCount: Int64(savedFiles.reduce(0) { $0 + $1.sizeBytes }),
                        countStyle: .file
                    ),
                    fileCount: savedFiles.count,
                    primaryFileType: firstFile.fileType,
                    mediaPreviewData: firstFile.mediaPreviewData,
                    files: savedFiles.map(NativeReceiveHistoryFile.init(file:))
                )
            )
        }
    }

    func saveUploadedFile(
        _ preparedFile: NativeReceiveTemporaryFile,
        displayName: String,
        fileType: NativeFileType,
        sizeBytes: Int,
        destination: NativeReceiveSaveDestination,
        completion: @escaping (NativeReceivedFile?) -> Void
    ) {
        let mediaPreviewData = mediaPreviewData(
            for: preparedFile.temporaryURL,
            fileType: fileType
        )
        saveReceivedTemporaryFile(
            preparedFile.temporaryURL,
            finalURL: preparedFile.finalURL,
            fileType: fileType,
            destination: destination
        ) { saved, assetIdentifier in
            guard saved else {
                try? FileManager.default.removeItem(at: preparedFile.temporaryURL)
                completion(nil)
                return
            }
            completion(
                NativeReceivedFile(
                    displayName: displayName,
                    fileType: fileType,
                    sizeBytes: sizeBytes,
                    mediaPreviewData: mediaPreviewData,
                    savedURLPath: destination == .folder ? preparedFile.finalURL.path : nil,
                    photoAssetIdentifier: assetIdentifier
                )
            )
        }
    }

    func deleteFiles(_ files: [NativeReceiveHistoryFile], completion: @escaping (Int) -> Void) {
        let group = DispatchGroup()
        let lock = NSLock()
        var failedCount = 0

        func markFailed() {
            lock.lock()
            failedCount += 1
            lock.unlock()
        }

        for file in files {
            if let path = file.savedURLPath {
                do {
                    try FileManager.default.removeItem(atPath: path)
                } catch {
                    markFailed()
                }
                continue
            }

            guard let assetIdentifier = file.photoAssetIdentifier else {
                continue
            }

            group.enter()
            PHPhotoLibrary.requestAuthorization(for: .readWrite) { status in
                guard status == .authorized || status == .limited else {
                    markFailed()
                    group.leave()
                    return
                }

                let assets = PHAsset.fetchAssets(withLocalIdentifiers: [assetIdentifier], options: nil)
                guard assets.count > 0 else {
                    group.leave()
                    return
                }

                PHPhotoLibrary.shared().performChanges {
                    PHAssetChangeRequest.deleteAssets(assets)
                } completionHandler: { deleted, _ in
                    if !deleted {
                        markFailed()
                    }
                    group.leave()
                }
            }
        }

        group.notify(queue: .main) {
            completion(failedCount)
        }
    }

    private func saveReceivedTemporaryFile(
        _ temporaryURL: URL,
        finalURL: URL,
        fileType: NativeFileType,
        destination: NativeReceiveSaveDestination,
        completion: @escaping (Bool, String?) -> Void
    ) {
        switch destination {
        case .folder:
            try? FileManager.default.removeItem(at: finalURL)
            do {
                try FileManager.default.moveItem(at: temporaryURL, to: finalURL)
                completion(true, nil)
            } catch {
                try? FileManager.default.removeItem(at: temporaryURL)
                completion(false, nil)
            }
        case .album:
            saveMediaToPhotoLibrary(fileURL: temporaryURL, fileType: fileType) { saved, assetIdentifier in
                try? FileManager.default.removeItem(at: temporaryURL)
                completion(saved, assetIdentifier)
            }
        }
    }

    private func mediaPreviewData(
        for fileURL: URL,
        fileType: NativeFileType
    ) -> Data? {
        switch fileType {
        case .image:
            return mediaPreviewImageData(for: fileURL)
        case .video:
            let asset = AVAsset(url: fileURL)
            let generator = AVAssetImageGenerator(asset: asset)
            generator.appliesPreferredTrackTransform = true
            generator.maximumSize = NativeReceivePreviewThumbnail.targetSize
            guard let image = try? generator.copyCGImage(
                at: CMTime(seconds: 0, preferredTimescale: 600),
                actualTime: nil
            ) else {
                return nil
            }
            return NativeReceivePreviewThumbnail.jpegData(from: UIImage(cgImage: image))
        case .document, .spreadsheet, .archive, .other:
            return nil
        }
    }

    private func mediaPreviewImageData(for fileURL: URL) -> Data? {
        guard let image = UIImage(contentsOfFile: fileURL.path) else {
            return nil
        }
        return NativeReceivePreviewThumbnail.jpegData(from: image)
    }

    private func saveMediaToPhotoLibrary(
        fileURL: URL,
        fileType: NativeFileType,
        completion: @escaping (Bool, String?) -> Void
    ) {
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
            guard status == .authorized || status == .limited else {
                completion(false, nil)
                return
            }
            var createdAssetIdentifier: String?
            PHPhotoLibrary.shared().performChanges {
                if fileType == .video {
                    let request = PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: fileURL)
                    createdAssetIdentifier = request?.placeholderForCreatedAsset?.localIdentifier
                } else {
                    let request = PHAssetChangeRequest.creationRequestForAssetFromImage(atFileURL: fileURL)
                    createdAssetIdentifier = request?.placeholderForCreatedAsset?.localIdentifier
                }
            } completionHandler: { saved, _ in
                completion(saved, saved ? createdAssetIdentifier : nil)
            }
        }
    }

    private func receivedDirectory() -> URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    private func temporaryReceivedURL(fileName: String, directory: URL) -> URL {
        directory.appendingPathComponent(".\(UUID().uuidString)-\(fileName.sanitizedFileName)")
    }
}

private enum NativeReceivePreviewThumbnail {
    static let targetSize = CGSize(width: 240, height: 240)

    static func jpegData(from image: UIImage) -> Data? {
        guard image.size.width > 0, image.size.height > 0 else {
            return nil
        }
        let scale = max(targetSize.width / image.size.width, targetSize.height / image.size.height)
        let scaledSize = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let origin = CGPoint(
            x: (targetSize.width - scaledSize.width) / 2,
            y: (targetSize.height - scaledSize.height) / 2
        )
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let thumbnail = UIGraphicsImageRenderer(size: targetSize, format: format).image { context in
            UIColor.white.setFill()
            context.cgContext.fill(CGRect(origin: .zero, size: targetSize))
            image.draw(in: CGRect(origin: origin, size: scaledSize))
        }
        return thumbnail.jpegData(compressionQuality: 0.82)
    }
}
