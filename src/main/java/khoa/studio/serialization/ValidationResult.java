package khoa.studio.serialization;

/** Output of a serializer: a command text when valid, an error otherwise (never both). */
public record ValidationResult(String output, String error) {
	public static ValidationResult ok(String output) {
		return new ValidationResult(output, null);
	}

	public static ValidationResult error(String message) {
		return new ValidationResult("", message);
	}

	public boolean valid() {
		return error == null;
	}
}
