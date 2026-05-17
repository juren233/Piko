import SwiftUI

struct NativeLoginView: View {
    @ObservedObject var authStore: NativeAuthStore
    let onSwitchToRegister: () -> Void

    @State private var email = ""
    @State private var password = ""

    private var canSubmit: Bool {
        !isSubmitting && !email.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && password.count >= 8
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
                        .textContentType(.password)
                }

                if let error = authStore.lastError {
                    Section {
                        Text(error.displayMessage)
                            .foregroundStyle(.red)
                    }
                }

                Section {
                    Button(NativeAuthLabels.signIn) {
                        Task {
                            await authStore.login(
                                email: email.trimmingCharacters(in: .whitespacesAndNewlines),
                                password: password
                            )
                        }
                    }
                    .disabled(!canSubmit)

                    Button(NativeAuthLabels.signUp) {
                        authStore.consumeError()
                        onSwitchToRegister()
                    }
                    .disabled(isSubmitting)
                }
            }
            .navigationTitle(NativeAuthLabels.signIn)
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}
