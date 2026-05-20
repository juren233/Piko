import SwiftUI
import UIKit

struct NativeSendView: View {
    @ObservedObject var model: NativePikoModel
    let onScrollProgressChange: (CGFloat) -> Void
    @StateObject private var titleCollapseState = PikoTitleCollapseState()
    @State private var showingPhotoPicker = false
    @State private var showingDocumentPicker = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                PikoCollapsingPageHeroHeader(
                    title: "发送",
                    subtitle: "选择目标和文件后直接传输",
                    metric: "\(model.selectedDeviceIds.count) 台 / \(model.selectedItemIds.count) 项",
                    collapseState: titleCollapseState
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
            .padding(.top, 68)
            .padding(.bottom, 136)
            .background(alignment: .top) {
                PikoScrollProgressObserver { progress in
                    titleCollapseState.update(progress)
                    onScrollProgressChange(progress)
                }
                    .frame(width: 0, height: 0)
            }
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
        .alert("P2P 传输失败", isPresented: Binding(
            get: { model.transferFailureMessage != nil },
            set: { if !$0 { model.transferFailureMessage = nil } }
        )) {
            Button("复制") {
                UIPasteboard.general.string = model.transferFailureMessage ?? ""
            }
            Button("好", role: .cancel) {
                model.transferFailureMessage = nil
            }
        } message: {
            Text(model.transferFailureMessage ?? "")
        }
        .task {
            model.refreshFriendsPresence()
        }
    }
}
