import SwiftUI

struct NativeRegisterView: View {
    @ObservedObject var authStore: NativeAuthStore
    let onSwitchToLogin: () -> Void

    @State private var email = ""
    @State private var password = ""
    @State private var username = ""
    @State private var nickname = ""

    private var canSubmit: Bool {
        !isSubmitting &&
        !email.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        password.count >= 8 &&
        (3...32).contains(username.trimmingCharacters(in: .whitespacesAndNewlines).count)
    }

    private var isSubmitting: Bool {
        authStore.state == .loading
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(NativeAuthLabels.signUp)
                .font(PikoFont.sectionTitle)
                .lineLimit(1)
                .truncationMode(.tail)

            TextField(NativeAuthLabels.email, text: $email)
                .textInputAutocapitalization(.never)
                .keyboardType(.emailAddress)
                .textContentType(.emailAddress)
                .autocorrectionDisabled()
                .pikoAuthField()

            SecureField(NativeAuthLabels.password, text: $password)
                .textContentType(.newPassword)
                .pikoAuthField()

            TextField(NativeAuthLabels.username, text: $username)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .pikoAuthField()

            TextField(NativeAuthLabels.nickname, text: $nickname)
                .pikoAuthField()

            if let error = authStore.lastError {
                Text(error.displayMessage)
                    .font(PikoFont.rowSubtitle)
                    .foregroundStyle(.red)
                    .lineLimit(2)
                    .truncationMode(.tail)
            }

            Button {
                Task {
                    await authStore.register(
                        email: email.trimmingCharacters(in: .whitespacesAndNewlines),
                        password: password,
                        username: username.trimmingCharacters(in: .whitespacesAndNewlines),
                        nickname: nickname.trimmingCharacters(in: .whitespacesAndNewlines).nilIfBlank
                    )
                }
            } label: {
                Text(NativeAuthLabels.signUp)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(!canSubmit)

            Button(NativeAuthLabels.signIn) {
                authStore.consumeError()
                onSwitchToLogin()
            }
            .font(PikoFont.button)
            .frame(maxWidth: .infinity, alignment: .trailing)
            .disabled(isSubmitting)
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 18)
    }
}
