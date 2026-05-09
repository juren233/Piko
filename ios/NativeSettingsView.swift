import SwiftUI

struct NativeSettingsView: View {
    @ObservedObject var model: NativePikoModel

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                PikoHeroPanel(
                    title: "设置",
                    subtitle: "设备、账号和传输偏好",
                    metric: "本机"
                )
                PikoSectionPanel(title: "传输") {
                    NativeSettingsRow(title: "自动接收", value: "可信设备")
                    VStack(alignment: .leading, spacing: 10) {
                        Text("图片视频保存位置")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        Picker(
                            "图片视频保存位置",
                            selection: Binding(
                                get: { model.mediaSaveLocation },
                                set: { model.updateMediaSaveLocation($0) }
                            )
                        ) {
                            ForEach(NativeMediaSaveLocation.allCases) { location in
                                Text(location.label).tag(location)
                            }
                        }
                        .pickerStyle(.segmented)
                    }
                    .padding(.vertical, 6)
                    NativeSettingsRow(title: "传输策略", value: "局域网优先")
                }
                PikoSectionPanel(title: "账号") {
                    NativeSettingsRow(title: "登录方式", value: "邮箱账号")
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 32)
            .padding(.bottom, 136)
        }
        .background(PikoPalette.pageBackground)
        .systemBarBackgrounds()
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
