package autonewsroller.verify;

import autonewsroller.model.FactPackage;

public record VerificationResult(boolean accepted,String reason,FactPackage factPackage) {}
