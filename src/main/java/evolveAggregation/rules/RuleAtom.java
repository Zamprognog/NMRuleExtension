package evolveAggregation.rules;


public class RuleAtom {
    private final String subject;
    private final String predicate;
    private final String object;

    public RuleAtom(String predicate, String subject, String object) {
        this.predicate = predicate;
        this.subject = subject;
        this.object = object;
    }

    public String getObject() {
        return object;
    }

    public String getPredicate() {
        return predicate;
    }

    public String getSubject() {
        return subject;
    }

    public Boolean isSubjectVariable() { return subject.length() == 1; }

    public Boolean isObjectVariable() { return object.length() == 1; }

    public boolean isUnary() {return object.length() > 1 || subject.length() >1;}

    //todo: double check the following
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RuleAtom atom = (RuleAtom) o;
        return predicate.equals(atom.predicate) &&
                subject.equals(atom.subject) &&
                object.equals(atom.object);
    }

    @Override
    public int hashCode() {
        int result = predicate.hashCode();
        result = 31 * result + subject.hashCode();
        result = 31 * result + object.hashCode();
        return result;
    }
}
