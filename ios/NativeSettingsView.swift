import SwiftUI

struct NativeSettingsView: View {
    @ObservedObject var model: NativePikoModel

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 18) {
                    PikoHeroPanel(
                        title: "设置",
                        subtitle: "设备、发现和接收状态",
                        metric: "本机"
                    )
                    PikoSectionPanel(title: "设备") {
                        NativeSettingsRow(title: "当前设备", value: model.currentDeviceName)
                        NativeSettingsRow(title: "局域网设备", value: "\(model.lanDevices.count)")
                    }
                    PikoSectionPanel(title: "数据") {
                        NativeSettingsRow(title: "接收记录", value: "\(model.receiveHistory.count)")
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 20)
                .padding(.bottom, 120)
            }
            .background(PikoPalette.pageGradient)
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
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Spacer()
            Text(value)
                .font(.title3.weight(.semibold))
                .lineLimit(1)
        }
        .padding(.vertical, 6)
    }
}
