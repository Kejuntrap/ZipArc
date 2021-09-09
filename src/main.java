import jdk.nashorn.internal.runtime.Debug;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.zip.CRC32;


enum compressMethod{
    UNCOMPRESS((char)0x0000),
    DEFLATE((char)0x0008);

    private final char code;
    compressMethod(char code){
        this.code = code;
    }
    public char getValue(){
        return code;
    }
}
public class main {

    //TODO ZIP64の表記の削除(エンドヘッダの)
    //TODO Deflate実装 (FUTURE)
    //TODO ファイル、フォルダの作成時期の取得とDOSの時刻形式のデータ出し (FUTURE)
    //TODO CRC32の確認
    //TODO 2^31-1 Byteより大きいファイルサイズのものが含まれている場合の警告(もしかしたら総量が2^31-1Byte未満かもしれない) (FUTURE)
    //TODO ディレクトリの.や\を用いてもきちんとZIPができるかの確認
    //TODO CRC32の計算の省略　(メモ化)

    static long SUM_ELEMENT_NUMBER = 0L;    // ファイルやフォルダ数
    static final int HEADERSIG = 0x04034b50;
    static final int CENTERSIG = 0x02014b50;
    static final char EXTRACT_LOCAL_VERSION = 0x0014;
    static final char GENERAL_PURPOSE_BIT_FLAGS = 0x0000;
    static final int FOLDER_CRC32 = 0x00000000; // フォルダのCRC32
    static final int FOLDER_SIZE = 0x00000000;  // フォルダサイズ(0)
    static int SUM_NAME_LENGTH = 0x00000000;    //ファイル名・フォルダ名の長さの合計
    static int SUM_UNCOMPRESS = 0x00000000;     //未圧縮ファイルの合計サイズ
    static int SUM_COMPRESS = 0x00000000;       //圧縮ファイルの合計サイズ
    static ArrayList<Integer> crcs=new ArrayList<Integer>();               //ファイルフォルダのCRC32を記録
    static ArrayList<Integer> position=new ArrayList<Integer>();             //データが入っているインデックス
    static int accumSize=0; //一時変数　今までに書き込んだ総量
    static final int LOCALFILEHEADER_BYTES = 30;  //ローカルファイルヘッダのサイズ
    static final int CENTRALDIRECTORYHEADER_SIZE = 46;       //セントラルディレクトリヘッダサイズ
    static final int ENDHEADER_SIZE = 22;   //エンドヘッダのサイズ
    static int LOCALHEADERAREA = 0; //ローカルファイルヘッダ+ファイルの末尾

    static long starttime = System.currentTimeMillis();
    static CRC32 crc32 = new CRC32();       //CRC32


    public static void main(String[] args) throws IOException {
        try{
            //ファイル作成
            FileOutputStream fos = new FileOutputStream("test.zip");
            // ファイル作成　完

            // 要素を全列挙
            DebugLog("Iteration begin.");
            ArrayList<Eleminfo> l = getAllelements("./");       // パスとファイル/フォルダ
            for(Eleminfo j:l){
                System.out.println("isFile: "+j.isFile+"\tpath: "+j.path);
            }
            SUM_ELEMENT_NUMBER = l.size();  // 要素数
            DebugLog("Iteration end.");
            // 要素を全列挙　完

            // 全要素のローカルファイルヘッダの作成
            DebugLog("LocalFileHeader making begin.");
            ArrayList<byte[]> localFileheaders = new ArrayList<byte[]>();

            for(int i=0; i<l.size(); i++){      //ローカルファイルヘッダの書き込み全般
                localFileheaders.add(makeLocalfileheader(l.get(i)));    //ローカルファイルヘッダの配列に入れる
                fos.write(makeLocalfileheader(l.get(i)));   //ローカルファイルヘッダを書き込む
                accumSize+=LOCALFILEHEADER_BYTES;       //ローカルファイルヘッダの値を加算
                fos.write(l.get(i).path.getBytes());        //パスを書き込む
                accumSize+=l.get(i).path.length();  //パス長を加算
                position.add(accumSize);    //次にファイルを書き込む位置　フォルダだと関係ない
                if(l.get(i).isFile){
                    Path f = Paths.get(l.get(i).path);
                    fos.write(Files.readAllBytes(f));       //未圧縮ファイル
                }
            }
            LOCALHEADERAREA=accumSize;
            DebugLog("LocalFileHeader making end.");
            // 全要素のローカルファイルヘッダの作成　完

            //セントラルディレクトリヘッダーの作成
            DebugLog("CentralDirectory begin");
            for(int i=0; i<l.size(); i++){
                byte[] centralDirectoryheader = new byte[46];
                ByteBuffer bf = ByteBuffer.wrap(centralDirectoryheader);
                bf.order(ByteOrder.LITTLE_ENDIAN);      //リトルエンディアンなので
                bf.putInt(CENTERSIG);
                bf.putChar(EXTRACT_LOCAL_VERSION);  //作成バージョン
                bf.putChar(EXTRACT_LOCAL_VERSION);  //解凍バージョン
                bf.putChar(GENERAL_PURPOSE_BIT_FLAGS);
                bf.putChar(compressMethod.UNCOMPRESS.getValue());
                Path p = Paths.get(l.get(i).path);
                if(l.get(i).isFile){
                    bf.putInt((int)Files.size(p)); //圧縮後サイズ
                    SUM_COMPRESS+=(int)Files.size(p); //圧縮後サイズの和
                    bf.putInt((int)Files.size(p)); //圧縮前サイズ
                    SUM_UNCOMPRESS+=(int)Files.size(p); //圧縮前サイズの和
                }else{
                    bf.putInt(FOLDER_SIZE); //圧縮後サイズ
                    bf.putInt(FOLDER_SIZE); //圧縮前サイズ
                }

                bf.putChar((char)l.get(i).path.length());   //パス長
                bf.putChar((char)0x0000);   //拡張フィールド長
                bf.putChar((char)0x0000);   //ファイルコメント長
                bf.putChar((char)i); //開始するディスク番号
                bf.putChar((char)0x0000);   //内部ファイル属性
                if(l.get(i).isFile){
                    bf.putInt(0x00000020);
                }else{
                    bf.putInt(0x00000010);
                }
                bf.putInt(accumSize-position.get(i));
                fos.write(centralDirectoryheader);
                fos.write(l.get(i).path.getBytes());
                accumSize+=CENTRALDIRECTORYHEADER_SIZE + l.get(i).path.length();
            }
            DebugLog("CentralDirectory end");

            //

            // エンドヘッダの作成
            DebugLog("Endheader begin");
            byte[] po = makeEndheader(l);
            fos.write(po);
            DebugLog("Endheader end");
            // エンドヘッダの作成　完


            fos.close();
            DebugLog("END");

        }catch (NullPointerException e){    // 存在がないなら終了
            e.printStackTrace();
        }










    }

    static byte[] makeLocalfileheader(Eleminfo e) throws IOException {      // ローカルファイルヘッダの作成
        byte[] ret = new byte[30];  //ファイル名をのぞくヘッダ arraylistと同じ順序なので
        ByteBuffer bf = ByteBuffer.wrap(ret);
        bf.order(ByteOrder.LITTLE_ENDIAN);      //リトルエンディアンなので
        if(! e.isFile){ // フォルダなら
            bf.putInt(HEADERSIG);       // ヘッダのシグニチャ
            bf.putChar(EXTRACT_LOCAL_VERSION);   //解凍に必要なバージョン
            bf.putChar(GENERAL_PURPOSE_BIT_FLAGS);  //汎用フラグ
            bf.putChar(compressMethod.UNCOMPRESS.getValue());       // 圧縮メソッド
            bf.putInt(FOLDER_CRC32);
            crcs.add(FOLDER_CRC32);
            bf.putInt(0x00000000);  //日付
            bf.putInt(FOLDER_SIZE); //圧縮後サイズ
            bf.putInt(FOLDER_SIZE); //圧縮前サイズ　
            bf.putChar((char)e.path.length());     // パスの長さなのでそのまま文字長で問題ないはず
            bf.putChar((char)0x0000); //エクストラフィールド
        }else{
            Path f = Paths.get(e.path);
            crc32.reset();  //? わからない
            bf.putInt(HEADERSIG);       // ヘッダのシグニチャ
            bf.putChar(EXTRACT_LOCAL_VERSION);   //解凍に必要なバージョン
            bf.putChar(GENERAL_PURPOSE_BIT_FLAGS);  //汎用フラグ
            bf.putChar(compressMethod.UNCOMPRESS.getValue());       // 圧縮メソッド
            crc32.update(Files.readAllBytes(f));
            bf.putInt((int)crc32.getValue());   //CRC32
            crcs.add((int)crc32.getValue());
            bf.putInt(0x00000000);  //日付
            bf.putInt((int)Files.size(f)); //圧縮後サイズ
            SUM_COMPRESS+=(int)Files.size(f); //圧縮後サイズの和　事前にDeflateで圧縮した後のサイズを渡すといいかも
            bf.putInt((int)Files.size(f)); //圧縮前サイズ
            SUM_UNCOMPRESS+=(int)Files.size(f);//圧縮前サイズの和　
            bf.putChar((char)e.path.length());     // パスの長さなのでそのまま文字長で問題ないはず
            bf.putChar((char)0x0000); //エクストラフィールド
        }
        return ret;
    }
    static class Eleminfo {     // 要素
        String path;
        boolean isFile;
    }
    static ArrayList<Eleminfo> getAllelements(String path){       //指定されたパス以下にあるすべてのファイル、フォルダを取得する 多分再帰を使ったほうがいいと思う
        ArrayList<Eleminfo> lists = new ArrayList<Eleminfo>();       //ファイル・フォルダのリスト
        ArrayList<String> folderList = new ArrayList<String>();     //未探索フォルダのリスト
        File fp = new File(path);   //今いる階層のファイル・フォルダパスを指定
        File fl[] = fp.listFiles();    // 今いる階層のファイル・フォルダリストを取得
        Eleminfo e;
        for (File f: fl){
            if(f.isFile()) {
                e = new Eleminfo();
                e.path = f.toString();
                e.isFile = true;
                SUM_NAME_LENGTH+=e.path.length();   //パスの文字数の合計を加算
                lists.add(e);
            }else if(f.isDirectory()){
                folderList.add(0,f.toString());
            }
        }
        while(folderList.size()>0){ //未探索のフォルダがある限り探し続ける
            e = new Eleminfo();
            e.path = folderList.get(0)+"\\";
            e.isFile = false;
            SUM_NAME_LENGTH+=e.path.length();   //パスの文字数の合計を加算
            lists.add(e);   //探索済みに入れる
            fp = new File(folderList.remove(0));   //一番先にある要素を取り出し削除
            fl= fp.listFiles();    // 今いる階層のファイル・フォルダリストを取得
            for(File f:fl){
                if(f.isFile()) {
                    e = new Eleminfo();
                    e.path = f.toString();
                    e.isFile = true;
                    SUM_NAME_LENGTH+=e.path.length();   //パスの文字数の合計を加算
                    lists.add(e);
                }else if(f.isDirectory()){
                    folderList.add(0,f.toString());
                }
            }
        }
        return lists;
    }
    static byte[] makeEndheader(ArrayList<Eleminfo> e){       //  セントラルディレクトリの末端を出力
        final int ENDSIG = 0x06054B50;  //セントラルディレクトリレコードのDIRシグニチャ
        final char NUMBER_OF_THIS_DISK = 0x0000;   //セントラルディレクトリが含まれるディスク番号
        final char NUMBER_OF_THE_DISK_START = 0x0000;       // セントラルディレクトリが始まるディスク番号　通常0xFFFF
        final char NUMBER_OF_ENTRIES_ON_THIS_DISK = (char) (SUM_ELEMENT_NUMBER);        // このディスクに含まれるセントラルディレクトリのエントリ数　通常0xFFFF
        final char NUMBER_OF_ENTRIES_IN_THE_CDIRECTORY = (char) (SUM_ELEMENT_NUMBER);        // セントラルディレクトリの総エントリ数　通常0xFFFF
        final int SIZE_OF_CDIRECTORY = accumSize - LOCALHEADERAREA;      // セントラルディレクトリのサイズ セントラルディレクトリまでからローカルファイルヘッダを引けば求まる
        final int OFFSET_OF_START_CDIRECTORY = 0x001E * e.size() + SUM_NAME_LENGTH + SUM_COMPRESS;  // セントラルディレクトリの始まるディスクの位置はローカルファイルヘッダの和+パス名の和+圧縮ファイルサイズの和
        final char ZIP_COMMENT =(char)0x0000;     //コメント
        // ここでの普通はZIP64

        //セントラルディレクトリレコード（最後）
        byte[] ret = new byte[22];
        ByteBuffer bf = ByteBuffer.wrap(ret);
        bf.order(ByteOrder.LITTLE_ENDIAN);
        bf.putInt(ENDSIG);
        bf.putChar(NUMBER_OF_THIS_DISK);
        bf.putChar(NUMBER_OF_THE_DISK_START);
        bf.putChar(NUMBER_OF_ENTRIES_ON_THIS_DISK);
        bf.putChar(NUMBER_OF_ENTRIES_IN_THE_CDIRECTORY);
        bf.putInt(SIZE_OF_CDIRECTORY);
        bf.putInt(OFFSET_OF_START_CDIRECTORY);
        bf.putChar(ZIP_COMMENT);

        return ret;
    }
    static void DebugLog(String s){
        long e = System.currentTimeMillis();
        long g = e - starttime;
        System.out.println("["+g/1000+String.format(".%03d",g%1000)+"] : "+s);
    }
}

/*

0byteのaというファイルを圧縮したときの最後のヘッダ

50 4B 05 06　　end of central dir signature
00 00           number of this disk
00 00           number of the disk with the start of the central directory
01 00              total number of entries in the central directory on this disk
01 00               total number of entries in the central directory
53 00 00 00         size of the central directory
1F 00 00 00     offset of start of central directory with respect to the starting disk number
00 00       comment

なにも淹れてないときのヘッダ

50 4B 05 06
00 00
00 00
00 00
00 00
00 00 00 00
00 00 00 00     1個目のセントラルディレクトリの始まる位置で、何もないを圧縮した場合ローカルファイルヘッダがないので0x00000000から始まりますよとなる。　1個以上あるならローカルファイルヘッダが30byte分あるので1Fから
00 00


 */