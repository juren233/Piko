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
        VStack(alignment: .leading, spacing: 14) {
            Text(NativeAuthLabels.signIn)
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
                .textContentType(.password)
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
                    await authStore.login(
                        email: email.trimmingCharacters(in: .whitespacesAndNewlines),
                        password: password
                    )
                }
            } label: {
                Text(NativeAuthLabels.signIn)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(!canSubmit)

            Button(NativeAuthLabels.signUp) {
                authStore.consumeError()
                onSwitchToRegister()
            }
            .font(PikoFont.button)
            .frame(maxWidth: .infinity, alignment: .trailing)
            .disabled(isSubmitting)
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 18)
    }
}

extension View {
    func pikoAuthField() -> some View {
        textFieldStyle(.roundedBorder)
            .font(PikoFont.rowTitle)
            .lineLimit(1)
            .minimumScaleFactor(0.88)
            .truncationMode(.tail)
    }
}
