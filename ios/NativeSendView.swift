import SwiftUI

struct NativeSendView: View {
    @ObservedObject var model: NativePikoModel
    @State private var showingPhotoPicker = false
    @State private var showingDocumentPicker = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                PikoHeroPanel(
                    title: "发送",
                    subtitle: "选择目标和文件后直接传输",
                    metric: "\(model.selectedDeviceIds.count) 台 / \(model.selectedItemIds.count) 项"
                )
                if model.transferIsVisible {
                    NativeTransferSection(model: model)
                }
                NativeDeviceSection(
                    title: "我的设备",
                    devices: model.myDevices,
                    selectedDeviceIds: model.selectedDeviceIds,
                    onDeviceClick: model.toggleDevice
                )
                NativeDeviceSection(
                    title: "局域网设备",
                    devices: model.lanDevices,
                    selectedDeviceIds: model.selectedDeviceIds,
                    onDeviceClick: model.toggleDevice,
                    emptyText: model.discoveryLabel
                )
                NativeDeviceSection(
                    title: "我的好友",
                    devices: model.friendDevices,
                    selectedDeviceIds: model.selectedDeviceIds,
                    onDeviceClick: model.toggleDevice
                )
                NativeImageSection(
                    model: model,
                    showingPhotoPicker: $showingPhotoPicker
                )
                NativeFileSection(
                    model: model,
                    showingDocumentPicker: $showingDocumentPicker
                )
            }
            .padding(.horizontal, 24)
            .padding(.top, 32)
            .padding(.bottom, 136)
        }
        .background(PikoPalette.pageBackground)
        .systemBarBackgrounds()
        .overlay(alignment: .bottom) {
            if model.canSend {
                NativeFloatingSendButton(action: model.sendSelectedItems)
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
    }
}
