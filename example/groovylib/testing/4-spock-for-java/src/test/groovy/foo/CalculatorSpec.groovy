package foo

def "Calculator add: #a + #b = #result"(){
    expect:
    Calculator.add(a, b) == result

    where:
    a  | b
    2  | 2
    -2 | -2
    result = a + b
}