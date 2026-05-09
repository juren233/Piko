import SwiftUI

struct NativeReceiveView: View {
    @ObservedObject var model: NativePikoModel

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 18) {
                    PikoHeroPanel(
                        title: "Piko",
                        subtitle: "接收记录和本机收件箱",
                        metric: "\(model.receiveHistory.count) 次"
                    )
                if model.receiveHistory.isEmpty {
                    NativeReceiveEmptyState()
                } else {
                    NativeReceiveHistoryList(items: model.receiveHistory)
                }
            }
                .padding(.horizontal, 20)
                .padding(.top, 20)
                .padding(.bottom, 120)
            }
            .background(PikoPalette.pageGradient)
            .systemBarBackgrounds()
            .navigationTitle("Piko")
            .navigationBarTitleDisplayMode(.inline)
        }
        .navigationViewStyle(.stack)
    }
}

private struct NativeReceiveEmptyState: View {
    var body: some View {
        PikoEmptyPlane(text: "还没有接收过文件") {
            Image(uiImage: LucideTabIcon.download.image)
                .resizable()
                .frame(width: 54, height: 54)
                .foregroundStyle(PikoPalette.accent)
        }
    }
}

private struct NativeReceiveHistoryList: View {
    let items: [NativeReceiveHistoryItem]

    var body: some View {
        PikoSectionPanel(title: "最近接收") {
            VStack(spacing: 10) {
                ForEach(items) { item in
                    HStack(spacing: 12) {
                        RoundedRectangle(cornerRadius: 2)
                            .fill(PikoPalette.accent)
                            .frame(width: 4, height: 52)
                        VStack(alignment: .leading, spacing: 6) {
                            Text(item.title)
                                .font(.headline)
                                .lineLimit(1)
                            Text(item.subtitle)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                        Spacer()
                    }
                    .padding(14)
                    .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 22, style: .continuous))
                }
            }
        }
    }
}
