/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1997-2007 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package com.sun.enterprise.v3.admin;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;

import org.glassfish.api.admin.AdminCommand;
import org.glassfish.api.admin.AdminCommandContext;
import org.glassfish.api.I18n;
import org.glassfish.api.Param;
import org.glassfish.api.ActionReport;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.annotations.Scoped;
import org.jvnet.hk2.annotations.Inject;
import org.jvnet.hk2.component.PerLookup;
import org.jvnet.hk2.config.ConfigSupport;
import org.jvnet.hk2.config.SingleConfigCode;
import com.sun.enterprise.config.serverbeans.Configs;
import com.sun.enterprise.config.serverbeans.Config;
import com.sun.enterprise.util.LocalStringManagerImpl;
import com.sun.enterprise.config.serverbeans.AuthRealm;
import com.sun.enterprise.security.auth.realm.file.FileRealm;
import com.sun.enterprise.security.auth.realm.BadRealmException;
import com.sun.enterprise.security.auth.realm.NoSuchUserException;
import com.sun.enterprise.security.auth.realm.NoSuchRealmException;
import com.sun.enterprise.security.auth.realm.User;
import com.sun.enterprise.config.serverbeans.Property;
import com.sun.enterprise.config.serverbeans.SecurityService;

import java.beans.PropertyVetoException;

/**
 * Create File User Command
 * Usage: create-file-user [--terse=false] [--echo=false] [--interactive=true] 
 *        [--host localhost] [--port 4848|4849] [--secure | -s] 
 *        [--user admin_user] [--passwordfile file_name] 
 *        [--groups user_groups[:user_groups]*] [--authrealmname authrealm_name]
 *        [--target target(Default server)] username *
 *
 * @author Nandini Ektare
 */

@Service(name="create-file-user")
@Scoped(PerLookup.class)
@I18n("create.file.user")
public class CreateFileUser implements AdminCommand {
    
    final private static LocalStringManagerImpl localStrings = 
        new LocalStringManagerImpl(CreateFileUser.class);    

//  @Param(name="groups", optional=true)
//  List<String> groups;

    @Param(name="userpasswordfile")
    String passwordFile;

    @Param(name="groups", optional=true)
    String groups;

    @Param(name="authrealmname", optional=true)
    String authRealmName;
    
    @Param(optional=true)
    String target;

    @Param(name="username", primary=true)
    String userName;

    @Inject
    Configs configs;

    /**
     * Executes the command with the command parameters passed as Properties
     * where the keys are the paramter names and the values the parameter values
     *
     * @param context information
     */
    public void execute(AdminCommandContext context) {
        
        final ActionReport report = context.getActionReport();

        List <Config> configList = configs.getConfig();
        Config config = configList.get(0);
        SecurityService securityService = config.getSecurityService();

        // ensure we have the file authrealm
        AuthRealm fileAuthRealm = null;        
        for (AuthRealm authRealm : securityService.getAuthRealm()) {
            if (authRealm.getName().equals(authRealmName))                 
                fileAuthRealm = authRealm;            
        }        
        if (fileAuthRealm == null) {
            report.setMessage(localStrings.getLocalString(
                "create.fileuser.filerealm.notfound",
                "There is no File realm to perform this operation"));
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            return;                                            
        }
        
        // Get FileRealm class name, match it with what is expected.
        String fileRealmClassName = fileAuthRealm.getClassname();
        
        // Report error if provided impl is not the one expected
        if (fileRealmClassName != null && 
            !fileRealmClassName.equals(
                "com.sun.enterprise.security.auth.realm.file.FileRealm")) {
            report.setMessage(
                localStrings.getLocalString(
                    "create.fileuser.realm.notsupported",
                    "Configured FileRealm is not supported."));
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            return;                
        }

        // ensure we have the file associated with the authrealm
        String keyFile = null;
        for (Property fileProp : fileAuthRealm.getProperty()) {
            if (fileProp.getName().equals("file"))
                keyFile = fileProp.getValue();
        }
        if (keyFile == null) {
            report.setMessage(
                localStrings.getLocalString("create.fileuser.keyfile.notfound",
                "There is no physical file associated with this Filerealm"));
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            return;                                            
        }
        
        // Now get all inputs ready. userid and groups are straightforward but
        // password is tricky. It is stored in the file passwordfile passed 
        // through the CLI options. It is stored under the name 
        // AS_ADMIN_USERPASSWORD. Fetch it from there.
        String password = fetchPassword();
        if (password == null) {
            report.setMessage(localStrings.getLocalString(
                "create.fileuser.keyfile.notreadable", "User password cannot " +
                "be read from the file associated with this Filerealm"));
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            return;
        }
                    
        // We have the right impl so let's get to checking existing user and 
        // adding one if one does not exist
        FileRealm fr = null;
        try {
            fr = new FileRealm(keyFile);            
        } catch(BadRealmException e) {
            report.setMessage(
                localStrings.getLocalString(
                    "create.fileuser.realm.corrupted",
                    "Configured FileRealm is corrupted."));
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            report.setFailureCause(e);
            return;                
        } catch(NoSuchRealmException e) {
            report.setMessage(
                localStrings.getLocalString(
                    "create.fileuser.realm.notsupported",
                    "Configured FileRealm is not supported."));
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            report.setFailureCause(e);
            return;                            
        }
        try {
            User user = fr.getUser(userName);
        } catch(NoSuchUserException e) {
            // Ignore. I want this exception. Now I can add the user
        }
        try {
            String[] groups1 = {groups}; 
            fr.addUser(userName, password, groups1);
            fr.writeKeyFile(keyFile);
        } catch (Exception e) {
            report.setMessage(
                localStrings.getLocalString("create.fileuser.useradd.failed",
                "Adding User to this Filerealm failed"));
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            report.setFailureCause(e);
            return;
        }        
    }
        
    private String fetchPassword() {
        String password = null;
        File passwdFile = new File(passwordFile);
        InputStream is = null;
        try {
            is = new BufferedInputStream(new FileInputStream(passwdFile));
            Properties prop = new Properties();
            prop.load(is);            
            for (Enumeration en=prop.propertyNames(); en.hasMoreElements();) {
                String entry = (String)en.nextElement();
                if (entry.equals("AS_ADMIN_USERPASSWORD")) {                    
                    password = prop.getProperty(entry);
                    break;
                }
            }
            return password;
        } catch(Exception e) {
            return null;
        } finally {
            try {
                if (is != null) is.close();
            } catch(final Exception ignore){}
        }        
    }
}
