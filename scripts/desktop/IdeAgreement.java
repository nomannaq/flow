import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.ide.gdpr.EndUserAgreement;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Read-only by default. Acceptance requires the user's exact document hash. */
class IdeAgreement {
    public static void main(String[] args) throws Exception {
        var document = EndUserAgreement.getLatestDocument();
        var output = Path.of(args[0]);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(document.getText().getBytes(StandardCharsets.UTF_8)));
        Files.writeString(output.resolveSibling("ide-agreement.html"), document.getText());
        boolean acceptedBefore = document.isAccepted();
        if (args.length == 2) {
            if (!hash.equals(args[1])) throw new IllegalArgumentException("Reviewed IDE agreement hash differs from this SDK");
            if (!acceptedBefore) EndUserAgreement.setAccepted(document);
        }
        var report = new JsonObject();
        report.addProperty("name", document.getName());
        report.addProperty("version", document.getVersion().toString());
        report.addProperty("sha256", hash);
        report.addProperty("acceptedBefore", acceptedBefore);
        report.addProperty("accepted", document.isAccepted());
        report.addProperty("explicitHashProvided", args.length == 2);
        Files.writeString(output, new Gson().toJson(report));
    }
}
