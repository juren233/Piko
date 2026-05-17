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
        NavigationStack {
            Form {
                Section {
                    TextField(NativeAuthLabels.email, text: $email)
                        .textInputAutocapitalization(.never)
                        .keyboardType(.emailAddress)
                        .textContentType(.emailAddress)
                        .autocorrectionDisabled()

                    SecureField(NativeAuthLabels.password, text: $password)
                        .textContentType(.newPassword)

                    TextField(NativeAuthLabels.username, text: $username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()

                    TextField(NativeAuthLabels.nickname, text: $nickname)
                }

                if let error = authStore.lastError {
                    Section {
                        Text(error.displayMessage)
                            .foregroundStyle(.red)
                    }
                }

                Section {
                    Button(NativeAuthLabels.signUp) {
                        Task {
                            await authStore.register(
                                email: email.trimmingCharacters(in: .whitespacesAndNewlines),
                                password: password,
                                username: username.trimmingCharacters(in: .whitespacesAndNewlines),
                                nickname: nickname.trimmingCharacters(in: .whitespacesAndNewlines).nilIfBlank
                            )
                        }
                    }
                    .disabled(!canSubmit)

                    Button(NativeAuthLabels.signIn) {
                        authStore.consumeError()
                        onSwitchToLogin()
                    }
                    .disabled(isSubmitting)
                }
            }
            .navigationTitle(NativeAuthLabels.signUp)
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}
