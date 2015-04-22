package org.multibit.hd.error_reporting;

import com.google.common.base.Charsets;
import com.google.common.io.Files;
import com.yammer.dropwizard.Service;
import com.yammer.dropwizard.config.Bootstrap;
import com.yammer.dropwizard.config.Environment;
import com.yammer.dropwizard.config.LoggingFactory;
import com.yammer.dropwizard.views.ViewMessageBodyWriter;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.eclipse.jetty.server.session.SessionHandler;
import org.multibit.hd.brit.crypto.PGPUtils;
import org.multibit.hd.error_reporting.health.ErrorReportingHealthCheck;
import org.multibit.hd.error_reporting.resources.PublicErrorReportingResource;
import org.multibit.hd.error_reporting.resources.RuntimeExceptionMapper;
import org.multibit.hd.error_reporting.servlets.AddressThrottlingFilter;
import org.multibit.hd.error_reporting.servlets.SafeLocaleFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;

/**
 * <p>Service to provide the following to application:</p>
 * <ul>
 * <li>Provision of access to resources</li>
 * </ul>
 * <p>Use <code>java -jar error-reporting-service-develop-SNAPSHOT.jar server config.yml</code> to start</p>
 *
 * @since 0.0.1
 *  
 */
public class ErrorReportingService extends Service<ErrorReportingConfiguration> {

  private static final Logger log = LoggerFactory.getLogger(ErrorReportingService.class);

  /**
   * The error reporting support directory
   */
  private static final String ERROR_REPORTING_DIRECTORY = "/var/error-reporting";

  /**
   * The service public key
   */
  private final String servicePublicKey;

  /**
   * Main entry point to the application
   *
   * @param args CLI arguments
   *
   * @throws Exception
   */
  public static void main(String[] args) throws Exception {

    // Start the logging factory
    LoggingFactory.bootstrap();

    // Securely read the password from the console
    final char[] password = readPassword();

    System.out.print("Crypto files ");
    // PGP decrypt the file (requires the private key ring that is password protected)
    final File errorReportingDirectory = getErrorReportingDirectory();
    final File secretKeyringFile = getSecretKeyringFile(errorReportingDirectory);
    final File publicKeyFile = getPublicKeyFile(errorReportingDirectory);
    final File testCryptoFile = getTestCryptoFile(errorReportingDirectory);
    System.out.println("OK");

    System.out.print("Crypto keys ");
    try {
      // Attempt to encrypt the test file
      ByteArrayOutputStream armoredOut = new ByteArrayOutputStream(1024);
      PGPPublicKey publicKey = PGPUtils.readPublicKey(new FileInputStream(publicKeyFile));
      PGPUtils.encryptFile(armoredOut, testCryptoFile, publicKey);

      // Attempt to decrypt the test file
      ByteArrayInputStream armoredIn = new ByteArrayInputStream(armoredOut.toByteArray());
      ByteArrayOutputStream decryptedOut = new ByteArrayOutputStream(1024);
      PGPUtils.decryptFile(armoredIn, decryptedOut, new FileInputStream(secretKeyringFile), password);

      // Verify that the decryption was successful
      String testCrypto = decryptedOut.toString();
      System.out.println(testCrypto);

      if (!"OK".equals(testCrypto)) {
        System.err.println("FAIL");
        System.exit(-1);
      }

    } catch (PGPException e) {
      System.err.println("FAIL (" + e.getMessage() + "). Checksum means password is incorrect.");
      System.exit(-1);
    }

    // Create the Matcher
    System.out.println("OK\nStarting service...\n");

    // Load the public key
    String publicKey = Files.toString(publicKeyFile, Charsets.UTF_8);

    // Must be OK to be here
    new ErrorReportingService(publicKey).run(args);

  }

  /**
   * @return The password taken from the console (or "password" if running in an IDE)
   */
  private static char[] readPassword() {

    Console console = System.console();
    final char[] password;
    if (console == null) {
      System.out.println("Could not obtain a console. Assuming an IDE and test data.");
      password = "password".toCharArray();
    } else {
      password = console.readPassword("%s", "Enter password:");
      if (password == null) {
        System.err.println("Could not read the password.");
        System.exit(-1);
      }
      System.out.println("Working...");
    }

    return password;

  }

  /**
   * @return The error reporting support directory
   */
  private static File getErrorReportingDirectory() {

    final File errorReportingDirectory = new File(ERROR_REPORTING_DIRECTORY);
    if (!errorReportingDirectory.exists()) {
      System.err.printf("Error reporting directory not present at '%s'.%n", errorReportingDirectory.getAbsolutePath());
      System.exit(-1);
    }

    return errorReportingDirectory;
  }

  private static File getSecretKeyringFile(File errorReportingDirectory) {

    File secretKeyringFile = new File(errorReportingDirectory, "fixtures/gpg/secring.gpg");
    if (!secretKeyringFile.exists()) {
      System.err.printf("Error reporting secret keyring not present at '%s'.%n", secretKeyringFile.getAbsolutePath());
      System.exit(-1);
    }

    return secretKeyringFile;
  }

  private static File getPublicKeyFile(File errorReportingDirectory) {

    File matcherPublicKeyFile = new File(errorReportingDirectory, "fixtures/gpg/public-key.asc");
    if (!matcherPublicKeyFile.exists()) {
      System.err.printf("Public key not present at '%s'.%n", matcherPublicKeyFile.getAbsolutePath());
      System.exit(-1);
    }

    return matcherPublicKeyFile;
  }

  private static File getTestCryptoFile(File errorReportingDirectory) throws IOException {

    File testCryptoFile = new File(errorReportingDirectory, "fixtures/gpg/test.txt");
    if (!testCryptoFile.exists()) {
      if (!testCryptoFile.createNewFile()) {
        System.err.printf("Could not create crypto test file: '%s'.%n", testCryptoFile.getAbsolutePath());
        System.exit(-1);
      }
      // Populate it with a simple test
      Writer writer = new FileWriter(testCryptoFile);
      writer.write("OK");
      writer.flush();
      writer.close();

    }

    return testCryptoFile;
  }

  public ErrorReportingService(String publicKey) {
    this.servicePublicKey = publicKey;
  }

  @Override
  public void initialize(Bootstrap<ErrorReportingConfiguration> bootstrap) {

    // Do nothing

  }

  @Override
  public void run(ErrorReportingConfiguration errorReportingConfiguration, Environment environment) throws Exception {

    log.info("Scanning environment...");

    // Configure environment
    environment.addResource(new PublicErrorReportingResource(servicePublicKey));

    // Health checks
    environment.addHealthCheck(new ErrorReportingHealthCheck());

    // Providers
    environment.addProvider(new ViewMessageBodyWriter());
    environment.addProvider(new RuntimeExceptionMapper());

    // Filters
    environment.addFilter(new SafeLocaleFilter(), "/*");
    if (errorReportingConfiguration.isProduction()) {
      environment.addFilter(new AddressThrottlingFilter(), "/*");
    } else {
      log.warn("*********************************************************************************");
      log.warn("* IP Address throttling is not active. Use 'production: true' on a live server. *");
      log.warn("*********************************************************************************");
    }

    // Session handler
    environment.setSessionHandler(new SessionHandler());

  }

}