package dev.diogo.paperspotlights.update;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A strictly parsed Semantic Version release tag.
 *
 * <p>Tags must use the {@code vMAJOR.MINOR.PATCH} form. Pre-release and build
 * metadata suffixes follow Semantic Versioning 2.0.0. Build metadata is retained
 * in the tag but does not affect precedence.</p>
 */
public final class SemVer implements Comparable<SemVer> {
    private static final int MAX_TAG_LENGTH = 255;
    private static final Pattern TAG_PATTERN = Pattern.compile(
            "^v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
                    + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
                    + "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$");
    private static final Pattern NUMERIC_IDENTIFIER = Pattern.compile("[0-9]+");

    private final BigInteger major;
    private final BigInteger minor;
    private final BigInteger patch;
    private final List<String> prereleaseIdentifiers;
    private final List<String> buildIdentifiers;
    private final String tag;

    private SemVer(
            BigInteger major,
            BigInteger minor,
            BigInteger patch,
            List<String> prereleaseIdentifiers,
            List<String> buildIdentifiers,
            String tag) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.prereleaseIdentifiers = List.copyOf(prereleaseIdentifiers);
        this.buildIdentifiers = List.copyOf(buildIdentifiers);
        this.tag = tag;
    }

    /** Parses a strict {@code vMAJOR.MINOR.PATCH} release tag. */
    public static SemVer parseTag(String tag) {
        Objects.requireNonNull(tag, "tag");
        if (tag.length() > MAX_TAG_LENGTH) {
            throw new IllegalArgumentException("Semantic Version tag is too long");
        }

        Matcher matcher = TAG_PATTERN.matcher(tag);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "Expected a Semantic Version tag in vMAJOR.MINOR.PATCH form");
        }

        List<String> prerelease = splitIdentifiers(matcher.group(4));
        for (String identifier : prerelease) {
            if (NUMERIC_IDENTIFIER.matcher(identifier).matches()
                    && identifier.length() > 1
                    && identifier.charAt(0) == '0') {
                throw new IllegalArgumentException(
                        "Numeric pre-release identifiers must not contain leading zeroes");
            }
        }

        return new SemVer(
                new BigInteger(matcher.group(1)),
                new BigInteger(matcher.group(2)),
                new BigInteger(matcher.group(3)),
                prerelease,
                splitIdentifiers(matcher.group(5)),
                tag);
    }

    private static List<String> splitIdentifiers(String identifiers) {
        return identifiers == null ? List.of() : Arrays.asList(identifiers.split("\\.", -1));
    }

    public BigInteger major() {
        return major;
    }

    public BigInteger minor() {
        return minor;
    }

    public BigInteger patch() {
        return patch;
    }

    public List<String> prereleaseIdentifiers() {
        return prereleaseIdentifiers;
    }

    public List<String> buildIdentifiers() {
        return buildIdentifiers;
    }

    public String tag() {
        return tag;
    }

    public boolean isPrerelease() {
        return !prereleaseIdentifiers.isEmpty();
    }

    @Override
    public int compareTo(SemVer other) {
        Objects.requireNonNull(other, "other");
        int comparison = major.compareTo(other.major);
        if (comparison != 0) {
            return comparison;
        }
        comparison = minor.compareTo(other.minor);
        if (comparison != 0) {
            return comparison;
        }
        comparison = patch.compareTo(other.patch);
        if (comparison != 0) {
            return comparison;
        }

        if (prereleaseIdentifiers.isEmpty()) {
            return other.prereleaseIdentifiers.isEmpty() ? 0 : 1;
        }
        if (other.prereleaseIdentifiers.isEmpty()) {
            return -1;
        }

        int sharedIdentifiers = Math.min(
                prereleaseIdentifiers.size(), other.prereleaseIdentifiers.size());
        for (int index = 0; index < sharedIdentifiers; index++) {
            comparison = comparePrereleaseIdentifier(
                    prereleaseIdentifiers.get(index), other.prereleaseIdentifiers.get(index));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(prereleaseIdentifiers.size(), other.prereleaseIdentifiers.size());
    }

    private static int comparePrereleaseIdentifier(String left, String right) {
        boolean leftNumeric = NUMERIC_IDENTIFIER.matcher(left).matches();
        boolean rightNumeric = NUMERIC_IDENTIFIER.matcher(right).matches();
        if (leftNumeric && rightNumeric) {
            int lengthComparison = Integer.compare(left.length(), right.length());
            return lengthComparison != 0 ? lengthComparison : left.compareTo(right);
        }
        if (leftNumeric != rightNumeric) {
            return leftNumeric ? -1 : 1;
        }
        return left.compareTo(right);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof SemVer other)) {
            return false;
        }
        return major.equals(other.major)
                && minor.equals(other.minor)
                && patch.equals(other.patch)
                && prereleaseIdentifiers.equals(other.prereleaseIdentifiers)
                && buildIdentifiers.equals(other.buildIdentifiers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch, prereleaseIdentifiers, buildIdentifiers);
    }

    @Override
    public String toString() {
        return tag;
    }
}
