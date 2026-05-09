import PhotosUI
import SwiftUI
import UniformTypeIdentifiers
import UIKit

struct NativePhotoPicker: UIViewControllerRepresentable {
    let onSelect: ([NativeTransferItem]) -> Void

    func makeUIViewController(context: Context) -> UIViewController {
        var configuration = PHPickerConfiguration(photoLibrary: .shared())
        configuration.filter = .images
        configuration.selectionLimit = 30
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
                guard provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) else {
                    continue
                }

                group.enter()
                provider.loadDataRepresentation(forTypeIdentifier: UTType.image.identifier) { data, _ in
                    defer {
                        group.leave()
                    }
                    guard let data else {
                        return
                    }
                    let name = provider.suggestedName.map { "\($0).jpg" } ?? "image-\(UUID().uuidString).jpg"
                    let item = NativeTransferItem(
                        id: "photo-\(UUID().uuidString)",
                        displayName: name,
                        fileType: .image,
                        data: data
                    )
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
                guard let data = try? Data(contentsOf: url) else {
                    return nil
                }
                return NativeTransferItem(
                    id: url.absoluteString,
                    displayName: url.lastPathComponent,
                    fileType: NativeFileType(url: url),
                    data: data
                )
            }
            onSelect(items)
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
