import SwiftUI

struct NativeSendView: View {
    @ObservedObject var model: NativePikoModel
    @State private var showingPhotoPicker = false
    @State private var showingDocumentPicker = false

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    PikoHeroPanel(
                        title: "发送",
                        subtitle: "选择目标和文件后直接传输",
                        metric: "\(model.selectedDeviceIds.count) 台 / \(model.selectedItemIds.count) 项"
                    )
                    NativeDeviceSection(model: model)
                    NativeItemSection(
                        model: model,
                        showingPhotoPicker: $showingPhotoPicker,
                        showingDocumentPicker: $showingDocumentPicker
                    )
                    NativeTransferSection(model: model)
                }
                .padding(.horizontal, 20)
                .padding(.top, 20)
                .padding(.bottom, 120)
            }
            .background(PikoPalette.pageGradient)
            .systemBarBackgrounds()
            .navigationTitle("发送")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(action: model.startDiscovery) {
                        Image(systemName: "arrow.clockwise")
                    }
                    .accessibilityLabel("刷新")
                }
            }
        }
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
        .navigationViewStyle(.stack)
    }
}
