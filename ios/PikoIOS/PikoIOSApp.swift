import Network
import PhotosUI
import SwiftUI
import UIKit
import UniformTypeIdentifiers

@main
struct PikoIOSApp: App {
    init() {
        let tabBarAppearance = UITabBarAppearance()
        tabBarAppearance.configureWithTransparentBackground()

        UITabBar.appearance().standardAppearance = tabBarAppearance
        UITabBar.appearance().scrollEdgeAppearance = tabBarAppearance
        UITabBar.appearance().isTranslucent = true
        UITabBar.appearance().shadowImage = UIImage()

        let navigationBarAppearance = UINavigationBarAppearance()
        navigationBarAppearance.configureWithTransparentBackground()
        UINavigationBar.appearance().standardAppearance = navigationBarAppearance
        UINavigationBar.appearance().scrollEdgeAppearance = navigationBarAppearance
        UINavigationBar.appearance().compactAppearance = navigationBarAppearance
        UINavigationBar.appearance().isTranslucent = true
    }

    var body: some Scene {
        WindowGroup {
            ImmersiveRootView {
                PikoRootView()
            }
            .ignoresSafeArea()
        }
    }
}

private struct PikoRootView: View {
    @StateObject private var model = NativePikoModel()

    var body: some View {
        ZStack {
            PikoIOSPalette.surface
                .ignoresSafeArea()

            TabView {
                NativeReceiveView(model: model)
                    .systemBarBackgrounds()
                    .tabItem {
                        Label {
                            Text("接收")
                        } icon: {
                            Image(uiImage: LucideTabIcon.download.image)
                        }
                    }

                NativeSendView(model: model)
                    .systemBarBackgrounds()
                    .tabItem {
                        Label {
                            Text("发送")
                        } icon: {
                            Image(uiImage: LucideTabIcon.send.image)
                        }
                    }

                NativeSettingsView(model: model)
                    .systemBarBackgrounds()
                    .tabItem {
                        Label {
                            Text("设置")
                        } icon: {
                            Image(uiImage: LucideTabIcon.settings.image)
                        }
                    }
            }
            .tint(PikoIOSPalette.accent)
            .background(PikoIOSPalette.surface.ignoresSafeArea())
        }
        .onAppear {
            model.startPresence()
            model.startDiscovery()
        }
    }
}

private struct ImmersiveRootView<Content: View>: UIViewControllerRepresentable {
    private let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    func makeUIViewController(context: Context) -> ImmersiveHostingController<Content> {
        let controller = ImmersiveHostingController(rootView: content)
        controller.view.backgroundColor = PikoIOSPalette.surfaceUIColor
        controller.view.isOpaque = true
        controller.edgesForExtendedLayout = .all
        controller.extendedLayoutIncludesOpaqueBars = true
        return controller
    }

    func updateUIViewController(_ controller: ImmersiveHostingController<Content>, context: Context) {
        controller.rootView = content
        controller.view.backgroundColor = PikoIOSPalette.surfaceUIColor
        controller.setNeedsStatusBarAppearanceUpdate()
        controller.setNeedsUpdateOfHomeIndicatorAutoHidden()
        controller.setNeedsUpdateOfScreenEdgesDeferringSystemGestures()
    }
}

private final class ImmersiveHostingController<Content: View>: UIHostingController<Content> {
    override var prefersHomeIndicatorAutoHidden: Bool {
        true
    }

    override var preferredScreenEdgesDeferringSystemGestures: UIRectEdge {
        .all
    }
}

private extension View {
    @ViewBuilder
    func systemBarBackgrounds() -> some View {
        if #available(iOS 16.0, *) {
            toolbarBackground(.hidden, for: .navigationBar, .tabBar)
        } else {
            self
        }
    }
}

private struct NativeReceiveView: View {
    @ObservedObject var model: NativePikoModel

    var body: some View {
        NavigationView {
            Group {
                if model.receiveHistory.isEmpty {
                    VStack(spacing: 18) {
                        Image(uiImage: LucideTabIcon.download.image)
                            .resizable()
                            .frame(width: 54, height: 54)
                            .foregroundStyle(.secondary)
                        Text("还没有接收过文件")
                            .font(.headline)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List(model.receiveHistory) { item in
                        VStack(alignment: .leading, spacing: 6) {
                            Text(item.title)
                                .font(.headline)
                            Text(item.subtitle)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 6)
                    }
                }
            }
            .background(PikoIOSPalette.surface)
            .systemBarBackgrounds()
            .navigationTitle("Piko")
            .navigationBarTitleDisplayMode(.inline)
        }
        .navigationViewStyle(.stack)
    }
}

private struct NativeSendView: View {
    @ObservedObject var model: NativePikoModel
    @State private var showingPhotoPicker = false
    @State private var showingDocumentPicker = false

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    deviceSection
                    itemSection
                    transferSection
                }
                .padding(.horizontal, 20)
                .padding(.top, 20)
                .padding(.bottom, 120)
            }
            .background(PikoIOSPalette.surface)
            .systemBarBackgrounds()
            .navigationTitle("发送")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        model.startDiscovery()
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .accessibilityLabel("刷新")
                }
            }
        }
        .overlay(alignment: .bottom) {
            if model.canSend {
                Button {
                    model.sendSelectedItems()
                } label: {
                    Label("发送", systemImage: "paperplane.fill")
                        .font(.headline)
                        .padding(.horizontal, 26)
                        .frame(height: 58)
                }
                .buttonStyle(.plain)
                .foregroundStyle(PikoIOSPalette.accent)
                .background(.ultraThinMaterial, in: Capsule())
                .overlay(
                    Capsule()
                        .stroke(.white.opacity(0.35), lineWidth: 1)
                )
                .shadow(color: .black.opacity(0.12), radius: 18, y: 8)
                .padding(.bottom, 92)
            }
        }
        .sheet(isPresented: $showingPhotoPicker) {
            NativePhotoPicker { items in
                model.addItems(items)
            }
        }
        .sheet(isPresented: $showingDocumentPicker) {
            NativeDocumentPicker { items in
                model.addItems(items)
            }
        }
        .navigationViewStyle(.stack)
    }

    private var deviceSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("局域网设备")
                    .font(.headline)
                Spacer()
                Text(model.discoveryLabel)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            if model.lanDevices.isEmpty {
                Text("暂无局域网设备")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding()
                    .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            } else {
                ForEach(model.lanDevices) { device in
                    Button {
                        model.toggleDevice(device.id)
                    } label: {
                        HStack(spacing: 12) {
                            Circle()
                                .fill(model.selectedDeviceIds.contains(device.id) ? PikoIOSPalette.accent : Color.secondary.opacity(0.25))
                                .frame(width: 36, height: 36)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(device.name)
                                    .font(.body.weight(.semibold))
                                Text(device.subtitle)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            if model.selectedDeviceIds.contains(device.id) {
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundStyle(PikoIOSPalette.accent)
                            }
                        }
                        .padding()
                        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private var itemSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("待发送")
                    .font(.headline)
                Spacer()
                Button("图片") {
                    showingPhotoPicker = true
                }
                Button("文件") {
                    showingDocumentPicker = true
                }
            }

            if model.items.isEmpty {
                Text("请选择图片或文件")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding()
                    .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            } else {
                ForEach(model.items) { item in
                    Button {
                        model.toggleItem(item.id)
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: item.systemImage)
                                .frame(width: 30)
                                .foregroundStyle(PikoIOSPalette.accent)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(item.displayName)
                                    .font(.body.weight(.medium))
                                    .lineLimit(1)
                                Text(item.sizeLabel)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            if model.selectedItemIds.contains(item.id) {
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundStyle(PikoIOSPalette.accent)
                            }
                        }
                        .padding()
                        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private var transferSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("传输")
                .font(.headline)
            Text(model.transferLabel)
                .font(.subheadline)
                .foregroundStyle(.secondary)
            if let progress = model.transferProgress {
                ProgressView(value: progress)
            }
        }
        .padding()
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

private struct NativeSettingsView: View {
    @ObservedObject var model: NativePikoModel

    var body: some View {
        NavigationView {
            List {
                NativeSettingsRow(title: "当前设备", value: model.currentDeviceName)
                NativeSettingsRow(title: "局域网设备", value: "\(model.lanDevices.count)")
                NativeSettingsRow(title: "接收记录", value: "\(model.receiveHistory.count)")
            }
            .background(PikoIOSPalette.surface)
            .systemBarBackgrounds()
            .navigationTitle("设置")
            .navigationBarTitleDisplayMode(.inline)
        }
        .navigationViewStyle(.stack)
    }
}

private struct NativeSettingsRow: View {
    let title: String
    let value: String

    var body: some View {
        HStack {
            Text(title)
            Spacer()
            Text(value)
                .foregroundColor(.secondary)
        }
    }
}

private final class NativePikoModel: ObservableObject {
    @Published var lanDevices: [NativeSendDevice] = []
    @Published var selectedDeviceIds: Set<String> = []
    @Published var items: [NativeTransferItem] = []
    @Published var selectedItemIds: Set<String> = []
    @Published var receiveHistory: [NativeReceiveHistoryItem] = []
    @Published var transferLabel = "等待发送"
    @Published var transferProgress: Double?
    @Published var discoveryLabel = "正在搜索"

    let currentDeviceName = UIDevice.current.name

    private let queue = DispatchQueue(label: "piko.native.network")
    private var listener: NWListener?
    private var browser: NWBrowser?

    var canSend: Bool {
        !selectedDeviceIds.isEmpty && !selectedItems.isEmpty && transferProgress == nil
    }

    private var selectedItems: [NativeTransferItem] {
        items.filter { selectedItemIds.contains($0.id) }
    }

    func startPresence() {
        guard listener == nil else {
            return
        }

        do {
            let listener = try NWListener(using: .tcp)
            listener.service = NWListener.Service(
                name: "Piko-\(currentDeviceName)",
                type: "_piko-share._tcp"
            )
            listener.newConnectionHandler = { [weak self] connection in
                self?.receive(connection)
            }
            listener.start(queue: queue)
            self.listener = listener
        } catch {
            DispatchQueue.main.async {
                self.transferLabel = "接收服务启动失败"
            }
        }
    }

    func startDiscovery() {
        browser?.cancel()
        discoveryLabel = "正在搜索"

        let browser = NWBrowser(for: .bonjour(type: "_piko-share._tcp", domain: "local."), using: .tcp)
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            guard let self else {
                return
            }

            let devices = results.compactMap { result -> NativeSendDevice? in
                guard case let .service(name, type, domain, _) = result.endpoint else {
                    return nil
                }
                guard name != "Piko-\(self.currentDeviceName)" else {
                    return nil
                }
                return NativeSendDevice(
                    id: "\(name).\(type).\(domain)",
                    name: name.replacingOccurrences(of: "Piko-", with: ""),
                    subtitle: domain.isEmpty ? type : domain,
                    endpoint: result.endpoint
                )
            }

            DispatchQueue.main.async {
                self.lanDevices = devices.sorted { $0.name < $1.name }
                self.selectedDeviceIds = self.selectedDeviceIds.intersection(Set(devices.map(\.id)))
                self.discoveryLabel = devices.isEmpty ? "暂无设备" : "已发现 \(devices.count) 台"
            }
        }
        browser.stateUpdateHandler = { [weak self] state in
            DispatchQueue.main.async {
                if case .failed = state {
                    self?.discoveryLabel = "搜索失败"
                }
            }
        }
        browser.start(queue: queue)
        self.browser = browser
    }

    func toggleDevice(_ id: String) {
        if selectedDeviceIds.contains(id) {
            selectedDeviceIds.remove(id)
        } else {
            selectedDeviceIds.insert(id)
        }
    }

    func addItems(_ newItems: [NativeTransferItem]) {
        let existingIds = Set(items.map(\.id))
        let uniqueItems = newItems.filter { !existingIds.contains($0.id) }
        items.append(contentsOf: uniqueItems)
        selectedItemIds.formUnion(uniqueItems.map(\.id))
    }

    func toggleItem(_ id: String) {
        if selectedItemIds.contains(id) {
            selectedItemIds.remove(id)
        } else {
            selectedItemIds.insert(id)
        }
    }

    func sendSelectedItems() {
        let targets = lanDevices.filter { selectedDeviceIds.contains($0.id) }
        let payloadItems = selectedItems
        guard !targets.isEmpty, !payloadItems.isEmpty else {
            return
        }

        transferLabel = "正在发送"
        transferProgress = 0

        queue.async {
            let totalBytes = max(payloadItems.reduce(0) { $0 + $1.data.count } * targets.count, 1)
            var sentBytes = 0

            for target in targets {
                let connection = NWConnection(to: target.endpoint, using: .tcp)
                let ready = DispatchSemaphore(value: 0)
                var failed = false

                connection.stateUpdateHandler = { state in
                    switch state {
                    case .ready:
                        ready.signal()
                    case .failed, .cancelled:
                        failed = true
                        ready.signal()
                    default:
                        break
                    }
                }
                let connectionQueue = DispatchQueue(label: "piko.native.connection.\(UUID().uuidString)")
                connection.start(queue: connectionQueue)
                ready.wait()

                guard !failed else {
                    connection.cancel()
                    DispatchQueue.main.async {
                        self.transferLabel = "发送失败"
                        self.transferProgress = nil
                    }
                    return
                }

                let header = NativeTransferProtocol.encodeHeader(items: payloadItems)
                if !self.send(header, over: connection) {
                    connection.cancel()
                    DispatchQueue.main.async {
                        self.transferLabel = "发送失败"
                        self.transferProgress = nil
                    }
                    return
                }

                for item in payloadItems {
                    guard self.send(item.data, over: connection) else {
                        connection.cancel()
                        DispatchQueue.main.async {
                            self.transferLabel = "发送失败"
                            self.transferProgress = nil
                        }
                        return
                    }
                    sentBytes += item.data.count
                    DispatchQueue.main.async {
                        self.transferProgress = Double(sentBytes) / Double(totalBytes)
                    }
                }
                connection.cancel()
            }

            DispatchQueue.main.async {
                self.transferLabel = "发送完成"
                self.transferProgress = nil
            }
        }
    }

    private func send(_ data: Data, over connection: NWConnection) -> Bool {
        let finished = DispatchSemaphore(value: 0)
        var succeeded = true
        connection.send(content: data, completion: .contentProcessed { error in
            succeeded = error == nil
            finished.signal()
        })
        finished.wait()
        return succeeded
    }

    private func receive(_ connection: NWConnection) {
        connection.stateUpdateHandler = { state in
            if case .ready = state {
                self.receiveNextChunk(from: connection, buffer: Data())
            }
        }
        connection.start(queue: queue)
    }

    private func receiveNextChunk(from connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { data, _, isComplete, _ in
            var nextBuffer = buffer
            if let data {
                nextBuffer.append(data)
            }

            if let transfer = NativeTransferProtocol.decodeTransfer(nextBuffer) {
                self.saveReceivedTransfer(transfer)
                connection.cancel()
                return
            }

            if isComplete {
                connection.cancel()
            } else {
                self.receiveNextChunk(from: connection, buffer: nextBuffer)
            }
        }
    }

    private func saveReceivedTransfer(_ transfer: NativeReceivedTransfer) {
        let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Piko", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

        for file in transfer.files {
            let url = directory.appendingPathComponent(file.displayName.sanitizedFileName)
            try? file.data.write(to: url, options: .atomic)
        }

        DispatchQueue.main.async {
            let names = transfer.files.map(\.displayName)
            self.receiveHistory.insert(
                NativeReceiveHistoryItem(
                    title: names.count == 1 ? names[0] : "\(names[0])+\(names.count - 1)个文件",
                    subtitle: "刚刚 - 来自局域网设备"
                ),
                at: 0
            )
        }
    }
}

private struct NativeSendDevice: Identifiable {
    let id: String
    let name: String
    let subtitle: String
    let endpoint: NWEndpoint
}

private struct NativeTransferItem: Identifiable {
    let id: String
    let displayName: String
    let fileType: NativeFileType
    let data: Data

    var sizeLabel: String {
        ByteCountFormatter.string(fromByteCount: Int64(data.count), countStyle: .file)
    }

    var systemImage: String {
        fileType == .image ? "photo" : "doc"
    }
}

private struct NativeReceiveHistoryItem: Identifiable {
    let id = UUID()
    let title: String
    let subtitle: String
}

private enum NativeFileType: Int {
    case document = 0
    case spreadsheet = 1
    case image = 2
    case video = 3
    case archive = 4
    case other = 5
}

private struct NativeReceivedFile {
    let displayName: String
    let data: Data
}

private struct NativeReceivedTransfer {
    let files: [NativeReceivedFile]
}

private enum NativeTransferProtocol {
    private static let magic = Data([0x50, 0x49, 0x4B, 0x4F])
    private static let version = 1

    static func encodeHeader(items: [NativeTransferItem]) -> Data {
        var data = Data()
        data.append(magic)
        data.appendInt32(version)
        data.appendInt32(items.count)
        for item in items {
            let nameBytes = Data(item.displayName.utf8)
            data.appendInt32(nameBytes.count)
            data.append(nameBytes)
            data.appendInt32(item.fileType.rawValue)
            data.appendInt64(item.data.count)
        }
        return data
    }

    static func decodeTransfer(_ data: Data) -> NativeReceivedTransfer? {
        var offset = 0
        guard data.count >= 12, data.readData(count: magic.count, offset: &offset) == magic else {
            return nil
        }
        guard data.readInt32(offset: &offset) == version else {
            return nil
        }
        guard let count = data.readInt32(offset: &offset), count >= 0 else {
            return nil
        }

        var metadata: [(String, Int)] = []
        for _ in 0..<count {
            guard let nameLength = data.readInt32(offset: &offset), nameLength >= 0 else {
                return nil
            }
            guard let nameData = data.readData(count: nameLength, offset: &offset) else {
                return nil
            }
            guard let name = String(data: nameData, encoding: .utf8) else {
                return nil
            }
            guard data.readInt32(offset: &offset) != nil else {
                return nil
            }
            guard let size = data.readInt64(offset: &offset), size >= 0 else {
                return nil
            }
            metadata.append((name, size))
        }

        var files: [NativeReceivedFile] = []
        for (name, size) in metadata {
            guard let bytes = data.readData(count: size, offset: &offset) else {
                return nil
            }
            files.append(NativeReceivedFile(displayName: name, data: bytes))
        }

        return NativeReceivedTransfer(files: files)
    }
}

private struct NativePhotoPicker: UIViewControllerRepresentable {
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

private struct NativeDocumentPicker: UIViewControllerRepresentable {
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

private extension NativeFileType {
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

private extension String {
    var sanitizedFileName: String {
        let invalid = CharacterSet(charactersIn: "/\\?%*|\"<>:")
        return components(separatedBy: invalid).joined(separator: "_")
    }
}

private extension Data {
    mutating func appendInt32(_ value: Int) {
        append(contentsOf: [
            UInt8((value >> 24) & 0xFF),
            UInt8((value >> 16) & 0xFF),
            UInt8((value >> 8) & 0xFF),
            UInt8(value & 0xFF),
        ])
    }

    mutating func appendInt64(_ value: Int) {
        for shift in stride(from: 56, through: 0, by: -8) {
            append(UInt8((value >> shift) & 0xFF))
        }
    }

    func readData(count: Int, offset: inout Int) -> Data? {
        guard count >= 0, offset + count <= self.count else {
            return nil
        }
        defer {
            offset += count
        }
        return subdata(in: offset..<(offset + count))
    }

    func readInt32(offset: inout Int) -> Int? {
        guard let bytes = readData(count: 4, offset: &offset) else {
            return nil
        }
        return bytes.reduce(0) { ($0 << 8) | Int($1) }
    }

    func readInt64(offset: inout Int) -> Int? {
        guard let bytes = readData(count: 8, offset: &offset) else {
            return nil
        }
        return bytes.reduce(0) { ($0 << 8) | Int($1) }
    }
}

private enum PikoIOSPalette {
    static let surfaceUIColor = UIColor(red: 255 / 255, green: 251 / 255, blue: 254 / 255, alpha: 1)
    static let accentUIColor = UIColor(red: 103 / 255, green: 80 / 255, blue: 164 / 255, alpha: 1)

    static var surface: Color {
        Color(uiColor: surfaceUIColor)
    }

    static var accent: Color {
        Color(uiColor: accentUIColor)
    }
}

private enum LucideTabIcon {
    case download
    case send
    case settings

    private var pathData: [String] {
        switch self {
        case .download:
            return [
                "M21,15v4a2,2 0,0 1,-2 2H5a2,2 0,0 1,-2 -2v-4",
                "M7,10l5,5 5,-5",
                "M12,15V3"
            ]
        case .send:
            return [
                "M22,2L15,22 11,13 2,9Z",
                "M22,2L11,13"
            ]
        case .settings:
            return [
                "M12.22,2h-0.44a2,2 0,0 0,-2 2v0.18a2,2 0,0 1,-1 1.73l-0.43,0.25a2,2 0,0 1,-2 0l-0.15,-0.08a2,2 0,0 0,-2.73 0.73l-0.22,0.38a2,2 0,0 0,0.73 2.73l0.15,0.1a2,2 0,0 1,1 1.72v0.51a2,2 0,0 1,-1 1.74l-0.15,0.09a2,2 0,0 0,-0.73 2.73l0.22,0.38a2,2 0,0 0,2.73 0.73l0.15,-0.08a2,2 0,0 1,2 0l0.43,0.25a2,2 0,0 1,1 1.73V20a2,2 0,0 0,2 2h0.44a2,2 0,0 0,2 -2v-0.18a2,2 0,0 1,1 -1.73l0.43,-0.25a2,2 0,0 1,2 0l0.15,0.08a2,2 0,0 0,2.73 -0.73l0.22,-0.39a2,2 0,0 0,-0.73 -2.73l-0.15,-0.08a2,2 0,0 1,-1 -1.74v-0.5a2,2 0,0 1,1 -1.74l0.15,-0.09a2,2 0,0 0,0.73 -2.73l-0.22,-0.38a2,2 0,0 0,-2.73 -0.73l-0.15,0.08a2,2 0,0 1,-2 0l-0.43,-0.25a2,2 0,0 1,-1 -1.73V4a2,2 0,0 0,-2 -2z",
                "M12,12m-3,0a3,3 0,1 0,6 0a3,3 0,1 0,-6 0"
            ]
        }
    }

    var image: UIImage {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 24, height: 24))
        let image = renderer.image { _ in
            UIColor.label.setStroke()

            let path = UIBezierPath()
            path.lineWidth = 2
            path.lineCapStyle = .round
            path.lineJoinStyle = .round
            pathData.forEach { data in
                var parser = LucidePathParser(data)
                path.append(parser.parse())
            }

            path.stroke()
        }

        return image.withRenderingMode(.alwaysTemplate)
    }
}

private struct LucidePathParser {
    private let data: String
    private var cursor: String.Index
    private var currentPoint = CGPoint.zero
    private var subpathStart = CGPoint.zero

    init(_ data: String) {
        self.data = data
        cursor = data.startIndex
    }

    mutating func parse() -> UIBezierPath {
        let path = UIBezierPath()
        var command: Character?

        while true {
            skipSeparators()
            guard cursor < data.endIndex else {
                break
            }

            if let explicitCommand = readCommand() {
                command = explicitCommand
            }

            guard let activeCommand = command else {
                break
            }

            switch activeCommand {
            case "M", "m":
                parseMove(path: path, relative: activeCommand == "m")
                command = activeCommand == "m" ? "l" : "L"
            case "L", "l":
                parseLines(path: path, relative: activeCommand == "l")
            case "H", "h":
                parseHorizontalLines(path: path, relative: activeCommand == "h")
            case "V", "v":
                parseVerticalLines(path: path, relative: activeCommand == "v")
            case "A", "a":
                parseArcs(path: path, relative: activeCommand == "a")
            case "Z", "z":
                path.close()
                currentPoint = subpathStart
                command = nil
            default:
                command = nil
            }
        }

        return path
    }

    private mutating func parseMove(path: UIBezierPath, relative: Bool) {
        guard let x = readNumber(), let y = readNumber() else {
            return
        }

        let point = resolvedPoint(x: x, y: y, relative: relative)
        path.move(to: point)
        currentPoint = point
        subpathStart = point
    }

    private mutating func parseLines(path: UIBezierPath, relative: Bool) {
        while hasNumberAhead() {
            guard let x = readNumber(), let y = readNumber() else {
                return
            }

            let point = resolvedPoint(x: x, y: y, relative: relative)
            path.addLine(to: point)
            currentPoint = point
        }
    }

    private mutating func parseHorizontalLines(path: UIBezierPath, relative: Bool) {
        while hasNumberAhead() {
            guard let x = readNumber() else {
                return
            }

            let point = CGPoint(x: relative ? currentPoint.x + x : x, y: currentPoint.y)
            path.addLine(to: point)
            currentPoint = point
        }
    }

    private mutating func parseVerticalLines(path: UIBezierPath, relative: Bool) {
        while hasNumberAhead() {
            guard let y = readNumber() else {
                return
            }

            let point = CGPoint(x: currentPoint.x, y: relative ? currentPoint.y + y : y)
            path.addLine(to: point)
            currentPoint = point
        }
    }

    private mutating func parseArcs(path: UIBezierPath, relative: Bool) {
        while hasNumberAhead() {
            guard
                let radiusX = readNumber(),
                let radiusY = readNumber(),
                let rotation = readNumber(),
                let largeArc = readNumber(),
                let sweep = readNumber(),
                let x = readNumber(),
                let y = readNumber()
            else {
                return
            }

            let endPoint = resolvedPoint(x: x, y: y, relative: relative)
            addArc(
                to: path,
                from: currentPoint,
                end: endPoint,
                radiusX: radiusX,
                radiusY: radiusY,
                rotation: rotation,
                largeArc: largeArc != 0,
                sweep: sweep != 0
            )
            currentPoint = endPoint
        }
    }

    private func resolvedPoint(x: CGFloat, y: CGFloat, relative: Bool) -> CGPoint {
        if relative {
            return CGPoint(x: currentPoint.x + x, y: currentPoint.y + y)
        }

        return CGPoint(x: x, y: y)
    }

    private mutating func addArc(
        to path: UIBezierPath,
        from start: CGPoint,
        end: CGPoint,
        radiusX: CGFloat,
        radiusY: CGFloat,
        rotation: CGFloat,
        largeArc: Bool,
        sweep: Bool
    ) {
        guard radiusX > 0, radiusY > 0, start != end else {
            path.addLine(to: end)
            return
        }

        let phi = rotation * .pi / 180
        let cosPhi = cos(phi)
        let sinPhi = sin(phi)
        let dx = (start.x - end.x) / 2
        let dy = (start.y - end.y) / 2
        let x1 = cosPhi * dx + sinPhi * dy
        let y1 = -sinPhi * dx + cosPhi * dy
        var rx = abs(radiusX)
        var ry = abs(radiusY)
        let lambda = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry)

        if lambda > 1 {
            let scale = sqrt(lambda)
            rx *= scale
            ry *= scale
        }

        let rx2 = rx * rx
        let ry2 = ry * ry
        let x12 = x1 * x1
        let y12 = y1 * y1
        let denominator = rx2 * y12 + ry2 * x12

        guard denominator != 0 else {
            path.addLine(to: end)
            return
        }

        let sign: CGFloat = largeArc == sweep ? -1 : 1
        let numerator = max(0, rx2 * ry2 - rx2 * y12 - ry2 * x12)
        let coefficient = sign * sqrt(numerator / denominator)
        let centerX1 = coefficient * (rx * y1 / ry)
        let centerY1 = coefficient * (-ry * x1 / rx)
        let center = CGPoint(
            x: cosPhi * centerX1 - sinPhi * centerY1 + (start.x + end.x) / 2,
            y: sinPhi * centerX1 + cosPhi * centerY1 + (start.y + end.y) / 2
        )
        let vectorStart = CGVector(dx: (x1 - centerX1) / rx, dy: (y1 - centerY1) / ry)
        let vectorEnd = CGVector(dx: (-x1 - centerX1) / rx, dy: (-y1 - centerY1) / ry)
        let startAngle = vectorAngle(from: CGVector(dx: 1, dy: 0), to: vectorStart)
        var deltaAngle = vectorAngle(from: vectorStart, to: vectorEnd)

        if !sweep && deltaAngle > 0 {
            deltaAngle -= 2 * .pi
        } else if sweep && deltaAngle < 0 {
            deltaAngle += 2 * .pi
        }

        let steps = max(8, Int(ceil(abs(deltaAngle) / (.pi / 8))))
        for step in 1...steps {
            let angle = startAngle + deltaAngle * CGFloat(step) / CGFloat(steps)
            let point = CGPoint(
                x: center.x + cosPhi * rx * cos(angle) - sinPhi * ry * sin(angle),
                y: center.y + sinPhi * rx * cos(angle) + cosPhi * ry * sin(angle)
            )
            path.addLine(to: point)
        }
    }

    private func vectorAngle(from start: CGVector, to end: CGVector) -> CGFloat {
        let dot = start.dx * end.dx + start.dy * end.dy
        let determinant = start.dx * end.dy - start.dy * end.dx
        return atan2(determinant, dot)
    }

    private mutating func readCommand() -> Character? {
        skipSeparators()
        guard cursor < data.endIndex else {
            return nil
        }

        let character = data[cursor]
        guard "MmLlHhVvAaZz".contains(character) else {
            return nil
        }

        cursor = data.index(after: cursor)
        return character
    }

    private mutating func readNumber() -> CGFloat? {
        skipSeparators()
        guard cursor < data.endIndex else {
            return nil
        }

        let start = cursor
        if data[cursor] == "-" || data[cursor] == "+" {
            cursor = data.index(after: cursor)
        }

        while cursor < data.endIndex {
            let character = data[cursor]
            if character.isNumber || character == "." {
                cursor = data.index(after: cursor)
            } else if character == "e" || character == "E" {
                cursor = data.index(after: cursor)
                if cursor < data.endIndex, data[cursor] == "-" || data[cursor] == "+" {
                    cursor = data.index(after: cursor)
                }
            } else {
                break
            }
        }

        guard start != cursor, let value = Double(String(data[start..<cursor])) else {
            cursor = start
            return nil
        }

        return CGFloat(value)
    }

    private func hasNumberAhead() -> Bool {
        var parser = self
        return parser.readNumber() != nil
    }

    private mutating func skipSeparators() {
        while cursor < data.endIndex {
            let character = data[cursor]
            if character == " " || character == "\n" || character == "\t" || character == "," {
                cursor = data.index(after: cursor)
            } else {
                break
            }
        }
    }
}
