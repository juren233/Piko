import AVFoundation
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers
import UIKit

struct NativeMediaPicker: UIViewControllerRepresentable {
    let onSelect: ([NativeTransferItem]) -> Void

    func makeUIViewController(context: Context) -> UIViewController {
        var configuration = PHPickerConfiguration(photoLibrary: .shared())
        configuration.filter = .any(of: [.images, .videos])
        configuration.selectionLimit = 0
        let controller = PHPickerViewController(configuration: configuration)
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onSelect: onSelect)
    }

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        private let onSelect: ([NativeTransferItem]) -> Void

        init(onSelect: @escaping ([NativeTransferItem]) -> Void) {
            self.onSelect = onSelect
        }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            picker.dismiss(animated: true)
            guard !results.isEmpty else {
                return
            }

            let group = DispatchGroup()
            let lock = NSLock()
            var items: [NativeTransferItem] = []

            for result in results {
                let provider = result.itemProvider
                let fileType: NativeFileType
                let typeIdentifier: String
                if provider.hasItemConformingToTypeIdentifier(UTType.movie.identifier) {
                    fileType = .video
                    typeIdentifier = UTType.movie.identifier
                } else if provider.hasItemConformingToTypeIdentifier(UTType.video.identifier) {
                    fileType = .video
                    typeIdentifier = UTType.video.identifier
                } else if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
                    fileType = .image
                    typeIdentifier = UTType.image.identifier
                } else {
                    continue
                }

                group.enter()
                provider.loadFileRepresentation(forTypeIdentifier: typeIdentifier) { url, _ in
                    defer {
                        group.leave()
                    }
                    guard let url else {
                        return
                    }
                    let name = mediaDisplayName(provider: provider, sourceURL: url, fileType: fileType)
                    guard let item = copyTransferItem(
                        from: url,
                        id: "media-\(UUID().uuidString)",
                        displayName: name,
                        fileType: fileType
                    ) else {
                        return
                    }
                    lock.lock()
                    items.append(item)
                    lock.unlock()
                }
            }

            group.notify(queue: .main) {
                self.onSelect(items)
            }
        }
    }
}

struct NativeDocumentPicker: UIViewControllerRepresentable {
    let onSelect: ([NativeTransferItem]) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let controller = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: true)
        controller.allowsMultipleSelection = true
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onSelect: onSelect)
    }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        private let onSelect: ([NativeTransferItem]) -> Void

        init(onSelect: @escaping ([NativeTransferItem]) -> Void) {
            self.onSelect = onSelect
        }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            let items = urls.compactMap { url -> NativeTransferItem? in
                let allowed = url.startAccessingSecurityScopedResource()
                defer {
                    if allowed {
                        url.stopAccessingSecurityScopedResource()
                    }
                }
                return copyTransferItem(
                    from: url,
                    id: url.absoluteString,
                    displayName: url.lastPathComponent,
                    fileType: NativeFileType(url: url)
                )
            }
            onSelect(items)
        }
    }
}

private func copyTransferItem(
    from sourceURL: URL,
    id: String,
    displayName: String,
    fileType: NativeFileType
) -> NativeTransferItem? {
    let fileManager = FileManager.default
    let directory = fileManager.temporaryDirectory.appendingPathComponent("PikoTransfers", isDirectory: true)
    guard (try? fileManager.createDirectory(at: directory, withIntermediateDirectories: true)) != nil else {
        return nil
    }
    let destination = directory.appendingPathComponent("\(UUID().uuidString)-\(displayName.sanitizedFileName)")
    try? fileManager.removeItem(at: destination)
    guard (try? fileManager.copyItem(at: sourceURL, to: destination)) != nil else {
        return nil
    }
    let sizeBytes = (try? destination.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
    return NativeTransferItem(
        id: id,
        displayName: displayName,
        fileType: fileType,
        fileURL: destination,
        sizeBytes: sizeBytes,
        previewData: mediaPreviewData(for: destination, fileType: fileType)
    )
}

private func mediaDisplayName(
    provider: NSItemProvider,
    sourceURL: URL,
    fileType: NativeFileType
) -> String {
    let fallbackExtension = sourceURL.pathExtension.isEmpty ? fileType.defaultFileExtension : sourceURL.pathExtension
    if let suggestedName = provider.suggestedName, !suggestedName.isEmpty {
        let suggestedURL = URL(fileURLWithPath: suggestedName)
        if suggestedURL.pathExtension.isEmpty {
            return "\(suggestedName).\(fallbackExtension)"
        }
        return suggestedName
    }
    return "\(fileType == .video ? "video" : "image")-\(UUID().uuidString).\(fallbackExtension)"
}

private func mediaPreviewData(for url: URL, fileType: NativeFileType) -> Data? {
    switch fileType {
    case .image:
        return UIImage(contentsOfFile: url.path)?.scaledPreviewData()
    case .video:
        let asset = AVURLAsset(url: url)
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        guard let cgImage = try? generator.copyCGImage(at: .zero, actualTime: nil) else {
            return nil
        }
        return UIImage(cgImage: cgImage).scaledPreviewData()
    default:
        return nil
    }
}

private extension UIImage {
    func scaledPreviewData() -> Data? {
        let targetSide: CGFloat = 320
        let scale = min(targetSide / max(size.width, size.height), 1)
        let targetSize = CGSize(width: max(size.width * scale, 1), height: max(size.height * scale, 1))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: targetSize, format: format).image { _ in
            draw(in: CGRect(origin: .zero, size: targetSize))
        }
        return image.jpegData(compressionQuality: 0.82)
    }
}

private extension NativeFileType {
    var defaultFileExtension: String {
        switch self {
        case .video:
            return "mov"
        case .image:
            return "jpg"
        default:
            return "dat"
        }
    }
}

extension NativeFileType {
    init(url: URL) {
        switch url.pathExtension.lowercased() {
        case "jpg", "jpeg", "png", "gif", "heic", "webp":
            self = .image
        case "mp4", "mov", "m4v":
            self = .video
        case "zip", "rar", "7z":
            self = .archive
        case "xls", "xlsx", "csv":
            self = .spreadsheet
        case "pdf", "doc", "docx", "txt", "md":
            self = .document
        default:
            self = .other
        }
    }
}
