package water.api;

import water.H2O;

import java.util.ArrayList;

/**
 * The handler provides import capabilities.
 *
 * <p>
 *   Currently import from local filesystem, hdfs and s3 is supported.
 * </p>
 */
public class ImportFilesHandler extends Handler {

  @SuppressWarnings("unused") // called through reflection by RequestServer
  public ImportFilesV3 importFiles(int version, ImportFilesV3 importFiles) {
    ArrayList<String> files = new ArrayList();
    ArrayList<String> keys = new ArrayList();
    ArrayList<String> fails = new ArrayList();
    ArrayList<String> dels = new ArrayList();

    H2O.getPM().importFiles(importFiles.path, files, keys, fails, dels);

    importFiles.files = files.toArray(new String[files.size()]);
    importFiles.destination_frames = keys.toArray(new String[keys.size()]);
    importFiles.fails = fails.toArray(new String[fails.size()]);
    importFiles.dels = dels.toArray(new String[dels.size()]);
    return importFiles;
  }

//
//
//  private void serveS3(){
//    Futures fs = new Futures();
//    assert path.startsWith("s3://");
//    path = path.substring(5);
//    int bend = path.indexOf('/');
//    if(bend == -1)bend = path.length();
//    String bucket = path.substring(0,bend);
//    String prefix = bend < path.length()?path.substring(bend+1):"";
//    AmazonS3 s3 = PersistS3.getClient();
//    if( !s3.doesBucketExist(bucket) )
//      throw new H2ONotFoundException("S3 Bucket " + bucket + " not found!");;
//    ArrayList<String> succ = new ArrayList<String>();
//    ArrayList<String> fail = new ArrayList<String>();
//    ObjectListing currentList = s3.listObjects(bucket, prefix);
//    while(true){
//      for(S3ObjectSummary obj:currentList.getObjectSummaries())
//        try {
//          succ.add(S3FileVec.make(obj,fs).toString());
//        } catch( Throwable e ) {
//          fail.add(obj.getKey());
//          Log.err("Failed to loadfile from S3: path = " + obj.getKey() + ", error = " + e.getClass().getName() + ", msg = " + e.getMessage());
//        }
//      if(currentList.isTruncated())
//        currentList = s3.listNextBatchOfObjects(currentList);
//      else
//        break;
//    }
//    keys = succ.toArray(new String[succ.size()]);
//    files = keys;
//    fails = fail.toArray(new String[fail.size()]);
//  }

//  private void serveHttp() {
//    try {
//      java.net.URL url = new URL(path);
//      Key k = Key.make(path);
//      InputStream is = url.openStream();
//      if( is == null ) {
//        Log.err("Unable to open stream to URL " + path);
//      }
//
//      UploadFileVec.readPut(k, is);
//      fails = new String[0];
//      String[] filesArr = { path };
//      files = filesArr;
//      String[] keysArr = { k.toString() };
//      keys = keysArr;
//    }
//    catch( Throwable e) {
//      String[] arr = { path };
//      fails = arr;
//      files = new String[0];
//      keys = new String[0];
//    }
//  }
//
//  // HTML builder
//  @Override protected boolean toHTML( StringBuilder sb ) {
//    if(files == null)return false;
//    if( files != null && files.length > 1 )
//      sb.append("<div class='alert'>")
//        .append(parseLink("*"+path+"*", "Parse all into hex format"))
//        .append(" </div>");
//
//    DocGen.HTML.title(sb,"files");
//    DocGen.HTML.arrayHead(sb);
//    for( int i=0; i<files.length; i++ )
//      sb.append("<tr><td><a href='"+parse()+"?source_key=").append(keys[i]).
//        append("'>").append(files[i]).append("</a></td></tr>");
//    DocGen.HTML.arrayTail(sb);
//
//    if( fails.length > 0 )
//      DocGen.HTML.array(DocGen.HTML.title(sb,"fails"),fails);
//    if( dels != null && dels.length > 0 )
//      DocGen.HTML.array(DocGen.HTML.title(sb,"Keys deleted before importing"),dels);
//    return true;
//  }
//
//  private boolean isBareS3NBucketWithoutTrailingSlash(String s) {
//    Pattern p = Pattern.compile("s3n://[^/]*");
//    Matcher m = p.matcher(s);
//    boolean b = m.matches();
//    return b;
//  }
//  private String parseLink(String k, String txt) { return Parse2.link(k, txt); }
//  String parse() { return "Parse2.query"; }
}
