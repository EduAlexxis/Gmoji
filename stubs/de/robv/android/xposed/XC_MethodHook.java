package de.robv.android.xposed;
public abstract class XC_MethodHook {
  public static class MethodHookParam { public java.lang.reflect.Member method; public Object thisObject; public Object[] args; public Object getResult(){return null;} public void setResult(Object r){} public Throwable getThrowable(){return null;} }
  public class Unhook { public void unhook(){} }
  protected void beforeHookedMethod(MethodHookParam p) throws Throwable {}
  protected void afterHookedMethod(MethodHookParam p) throws Throwable {}
}
