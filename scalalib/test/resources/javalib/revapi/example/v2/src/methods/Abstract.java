/*
 * Copyright 2014-2017 Lukas Krejci
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
public abstract class Abstract {

    public void abstractMethod() {}

    public abstract void concreteMethod();

    private static abstract class PrivateSuperClass {
        //this won't be reported as anything, because this class is private and all its public
        //inheritors are concrete and/or implement this method.
        public abstract void method();
    }

    private static abstract class PubliclyUsedPrivateSuperClass {
        public abstract void method();
    }

    public static class A extends PrivateSuperClass {
        public void method() {}
    }

    public static abstract class B extends PrivateSuperClass {
        public void method() {}
        public abstract PubliclyUsedPrivateSuperClass abstractMethod();
    }

    public static class C extends PubliclyUsedPrivateSuperClass {
        public void method() {}
    }
}
