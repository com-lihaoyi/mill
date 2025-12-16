final class InputWhitespaceCharacters {
  // Long line ----------------------------------------------------------------
  // Contains a tab ->	<- // violation 'Line contains a tab character.'
  // Contains trailing whitespace ->

  /**
   * Some javadoc.
   *
   * @param badFormat1 bad format
   * @param badFormat2 bad format
   * @param badFormat3 bad format
   * @return hack
   * @throws java.lang.Exception abc
   **/
  int test1(int badFormat1, int badFormat2,
  		final int badFormat3) // violation 'Line contains a tab character.'
      throws java.lang.Exception {
    return 0;
  }
}
