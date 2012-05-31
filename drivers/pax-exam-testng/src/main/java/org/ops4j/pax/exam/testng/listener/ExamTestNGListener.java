/*
 * Copyright 2011 Harald Wellmann
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.
 *
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ops4j.pax.exam.testng.listener;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.ops4j.pax.exam.Constants;
import org.ops4j.pax.exam.ExamConfigurationException;
import org.ops4j.pax.exam.ExceptionHelper;
import org.ops4j.pax.exam.TestAddress;
import org.ops4j.pax.exam.TestContainerException;
import org.ops4j.pax.exam.TestDirectory;
import org.ops4j.pax.exam.TestInstantiationInstruction;
import org.ops4j.pax.exam.TestProbeBuilder;
import org.ops4j.pax.exam.spi.ExamReactor;
import org.ops4j.pax.exam.spi.StagedExamReactor;
import org.ops4j.pax.exam.spi.reactors.ReactorManager;
import org.ops4j.pax.exam.util.Injector;
import org.ops4j.pax.exam.util.InjectorFactory;
import org.ops4j.spi.ServiceProviderFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.IHookCallBack;
import org.testng.IHookable;
import org.testng.IMethodInstance;
import org.testng.IMethodInterceptor;
import org.testng.ISuite;
import org.testng.ISuiteListener;
import org.testng.ITestClass;
import org.testng.ITestContext;
import org.testng.ITestNGMethod;
import org.testng.ITestResult;
import org.testng.internal.MethodInstance;
import org.testng.internal.NoOpTestClass;

public class ExamTestNGListener implements ISuiteListener, IMethodInterceptor, IHookable
{
    private static Logger LOG = LoggerFactory.getLogger( ExamTestNGListener.class );

    private StagedExamReactor reactor;

    private Map<String, TestAddress> methodToAddressMap = new HashMap<String, TestAddress>();

    private ReactorManager manager;

    private boolean useProbeInvoker;

    private boolean methodInterceptorCalled;

    private Object currentTestClassInstance;

    public ExamTestNGListener()
    {
        LOG.debug( "created ExamTestNGListener" );
    }

    private boolean isRunningInTestContainer( ISuite suite )
    {
        return suite.getName().equals( "PaxExamInternal" );
    }

    private boolean isRunningInTestContainer( ITestNGMethod method )
    {
        return method.getXmlTest().getSuite().getName().equals( "PaxExamInternal" );
    }

    public void onStart( ISuite suite )
    {
        if( !isRunningInTestContainer( suite ) )
        {
            manager = ReactorManager.getInstance();
            try
            {
                reactor = prepareReactor( suite );
                manager.beforeSuite( reactor );
            }
            catch ( Exception exc )
            {
                throw new TestContainerException( exc );
            }
        }
    }

    public void onFinish( ISuite suite )
    {
        if( !isRunningInTestContainer( suite ) )
        {
            if( currentTestClassInstance != null )
            {
                manager.afterClass( reactor, currentTestClassInstance.getClass() );
            }
            manager.afterSuite( reactor );
        }
    }

    private synchronized StagedExamReactor prepareReactor( ISuite suite )
        throws Exception
    {
        List<ITestNGMethod> methods = suite.getAllMethods();
        Class<?> testClass = methods.get( 0 ).getRealClass();
        disableConfigurationMethods( methods.get(0).getTestClass() );
        Object testClassInstance = testClass.newInstance();
        ExamReactor examReactor = manager.prepareReactor( testClass, testClassInstance );
        useProbeInvoker = !manager.getSystemType().equals( Constants.EXAM_SYSTEM_CDI );
        if( useProbeInvoker )
        {
            addTestsToReactor( examReactor, testClassInstance, methods );
        }
        return manager.stageReactor();
    }

    private void addTestsToReactor( ExamReactor reactor, Object testClassInstance,
            List<ITestNGMethod> methods )
        throws IOException, ExamConfigurationException
    {
        TestProbeBuilder probe = manager.createProbeBuilder( testClassInstance );
        for( ITestNGMethod m : methods )
        {
            TestAddress address = probe.addTest( m.getRealClass(), m.getMethodName() );
            manager.storeTestMethod( address, m );
        }
        reactor.addProbe( probe );
    }

    public void run( IHookCallBack callBack, ITestResult testResult )
    {
        if( isRunningInTestContainer( testResult.getMethod() ) )
        {
            runInTestContainer( callBack, testResult );
            return;
        }
        else
        {
            runByDriver( callBack, testResult );
        }
    }

    private void runInTestContainer( IHookCallBack callBack, ITestResult testResult )
    {
        Object testClassInstance = testResult.getInstance();
        inject( testClassInstance );
        callBack.runTestMethod( testResult );
        return;
    }

    private void inject( Object testClassInstance )
    {
        InjectorFactory injectorFactory =
            ServiceProviderFinder.loadUniqueServiceProvider( InjectorFactory.class );
        Injector injector = injectorFactory.createInjector();
        injector.injectFields( null, testClassInstance );
    }

    private void runByDriver( IHookCallBack callBack, ITestResult testResult )
    {
        LOG.info( "running {}", testResult.getName() );
        Object testClassInstance = testResult.getMethod().getInstance();
        if( testClassInstance != currentTestClassInstance )
        {
            if( currentTestClassInstance != null )
            {
                manager.afterClass( reactor, currentTestClassInstance.getClass() );
            }
            manager.beforeClass( reactor, testClassInstance );
            currentTestClassInstance = testClassInstance;
        }

        if( !useProbeInvoker )
        {
            callBack.runTestMethod( testResult );
            return;
        }

        TestAddress address = methodToAddressMap.get( testResult.getName() );
        TestAddress root = address.root();

        LOG.debug( "Invoke " + testResult.getName() + " @ " + address + " Arguments: "
                + root.arguments() );
        try
        {
            reactor.invoke( address );
            testResult.setStatus( ITestResult.SUCCESS );
        }
        catch ( Exception e )
        {
            Throwable t = ExceptionHelper.unwind( e );
            LOG.error( "Exception", e );
            testResult.setStatus( ITestResult.FAILURE );
            testResult.setThrowable( t );
        }
    }

    public List<IMethodInstance> intercept( List<IMethodInstance> methods, ITestContext context )
    {
        if( methodInterceptorCalled || !useProbeInvoker
                || isRunningInTestContainer( context.getSuite() ) )
        {
            return methods;
        }

        methodInterceptorCalled = true;
        TestDirectory testDirectory = TestDirectory.getInstance();
        List<IMethodInstance> newInstances = new ArrayList<IMethodInstance>();
        Set<TestAddress> targets = reactor.getTargets();
        for( TestAddress address : targets )
        {
            ITestNGMethod frameworkMethod =
                (ITestNGMethod) manager.lookupTestMethod( address.root() );
            Method javaMethod = frameworkMethod.getConstructorOrMethod().getMethod();
            ReactorTestNGMethod reactorMethod =
                new ReactorTestNGMethod( frameworkMethod, javaMethod, address );
            MethodInstance newInstance = new MethodInstance( reactorMethod );
            newInstances.add( newInstance );
            methodToAddressMap.put( reactorMethod.getMethodName(), address );
            testDirectory.add( address, new TestInstantiationInstruction( frameworkMethod
                .getRealClass().getName() + ";"
                    + javaMethod.getName() ) );
        }
        Collections.sort( newInstances, new IMethodInstanceComparator() );
        return newInstances;
    }

    private void disableConfigurationMethods( ITestClass klass )
    {
        NoOpTestClass testClass = (NoOpTestClass) klass;
        ITestNGMethod[] noMethods = new ITestNGMethod[0];
        testClass.setBeforeTestMethods( noMethods );
        try {
            Field field = NoOpTestClass.class.getDeclaredField( "m_beforeTestMethods" );
            field.setAccessible( true );
            field.set( testClass, noMethods );

            field = NoOpTestClass.class.getDeclaredField( "m_afterTestMethods" );
            field.setAccessible( true );
            field.set( testClass, noMethods );
        }
        catch (Exception exc)
        {
            throw new TestContainerException( exc );
        }
    }
}
