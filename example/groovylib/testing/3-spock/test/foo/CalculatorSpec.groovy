package foo

def "Calculator add: #a + #b = #expected"(){
    given:
    def sut = new Calculator()

    when:
    def result = sut.add(a, b)

    then:
    result == expected

    where:
    a  | b  | expected
    2  | 2  |  4
    -2 | -2 | -4
}